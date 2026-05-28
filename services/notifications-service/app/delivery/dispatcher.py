"""Dispatcher: routes a notification to the appropriate channel with retry + DLQ."""
from __future__ import annotations

import asyncio
import random
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Protocol

from app.config import Settings
from app.db.repositories import NotificationRepository
from app.db.session import Database
from app.delivery.email import EmailSender
from app.delivery.sms import SMSSender
from app.delivery.webhook import WebhookSender
from app.domain.enums import Channel
from app.domain.models import Notification
from app.observability.logging import get_logger
from app.observability.metrics import (
    dead_letter_total,
    dispatch_attempt_total,
    dispatch_latency_seconds,
    events_dispatched_total,
    inflight_dispatches,
)
from app.observability.otel import get_tracer
from app.templating.render import render_template

log = get_logger(__name__)
tracer = get_tracer("notifications.dispatcher")


class EventPublisher(Protocol):
    async def publish(self, topic: str, event: dict[str, Any]) -> None: ...


@dataclass(slots=True)
class DispatchRequest:
    """Materialised dispatch instruction handed to the dispatcher."""

    notification_id: uuid.UUID
    channel: Channel
    to: str
    subject: str | None
    template_key: str
    context: dict[str, Any] = field(default_factory=dict)
    correlation_id: str | None = None
    user_id: uuid.UUID | None = None


class Dispatcher:
    """Channel router with exponential-backoff retries and DLQ semantics."""

    def __init__(
        self,
        *,
        settings: Settings,
        database: Database,
        email_sender: EmailSender,
        sms_sender: SMSSender,
        webhook_sender: WebhookSender,
        publisher: EventPublisher,
    ) -> None:
        self._settings = settings
        self._db = database
        self._email = email_sender
        self._sms = sms_sender
        self._webhook = webhook_sender
        self._publisher = publisher

    async def dispatch(self, request: DispatchRequest) -> bool:
        """Attempt delivery with exponential backoff; persist outcome.

        Returns ``True`` on success, ``False`` if the notification was dead-lettered.
        """
        max_attempts = self._settings.delivery_max_attempts
        base = self._settings.delivery_backoff_base
        cap = self._settings.delivery_backoff_max
        start = time.perf_counter()
        inflight_dispatches.inc()
        last_error: str = ""
        attempts = 0
        try:
            with tracer.start_as_current_span("dispatcher.dispatch") as span:
                span.set_attribute("notification.id", str(request.notification_id))
                span.set_attribute("notification.channel", request.channel.value)
                span.set_attribute("notification.template", request.template_key)

                for attempt in range(1, max_attempts + 1):
                    attempts = attempt
                    try:
                        await self._send_once(request)
                    except Exception as exc:  # noqa: BLE001 — top-level retry boundary
                        last_error = f"{type(exc).__name__}: {exc}"
                        dispatch_attempt_total.labels(
                            channel=request.channel.value, outcome="error"
                        ).inc()
                        log.warning(
                            "dispatch_attempt_failed",
                            notification_id=str(request.notification_id),
                            attempt=attempt,
                            channel=request.channel.value,
                            error=last_error,
                        )
                        if attempt >= max_attempts:
                            break
                        await asyncio.sleep(_backoff_delay(attempt, base=base, cap=cap))
                        continue
                    else:
                        dispatch_attempt_total.labels(
                            channel=request.channel.value, outcome="success"
                        ).inc()
                        await self._record_success(request, attempts=attempt)
                        events_dispatched_total.labels(
                            channel=request.channel.value, template=request.template_key
                        ).inc()
                        await self._emit_notification_event(
                            request, status="SENT", attempts=attempt, error=None
                        )
                        return True

                await self._record_failure(
                    request, attempts=attempts, error=last_error, dead_lettered=True
                )
                dead_letter_total.labels(
                    channel=request.channel.value, template=request.template_key
                ).inc()
                await self._emit_notification_event(
                    request, status="FAILED", attempts=attempts, error=last_error
                )
                return False
        finally:
            inflight_dispatches.dec()
            dispatch_latency_seconds.labels(channel=request.channel.value).observe(
                time.perf_counter() - start
            )

    async def _send_once(self, request: DispatchRequest) -> None:
        if request.channel is Channel.EMAIL:
            html = render_template(request.template_key, request.context)
            subject = request.subject or request.context.get("subject") or "Notification"
            await self._email.send(
                to=request.to,
                subject=subject,
                html_body=html,
                from_addr=self._settings.smtp_from,
            )
        elif request.channel is Channel.SMS:
            body = render_template(request.template_key, request.context)
            await self._sms.send(to=request.to, body=body)
        elif request.channel is Channel.WEBHOOK:
            await self._webhook.send(to=request.to, payload=request.context)
        else:  # pragma: no cover — defensive
            raise ValueError(f"Unsupported channel: {request.channel}")

    async def _record_success(self, request: DispatchRequest, *, attempts: int) -> None:
        async with self._db.session() as session:
            repo = NotificationRepository(session)
            await repo.mark_sent(request.notification_id, attempts=attempts)
            await session.commit()

    async def _record_failure(
        self,
        request: DispatchRequest,
        *,
        attempts: int,
        error: str,
        dead_lettered: bool,
    ) -> None:
        async with self._db.session() as session:
            repo = NotificationRepository(session)
            await repo.mark_failure(
                request.notification_id,
                attempts=attempts,
                error=error,
                dead_lettered=dead_lettered,
            )
            await session.commit()

    async def _emit_notification_event(
        self,
        request: DispatchRequest,
        *,
        status: str,
        attempts: int,
        error: str | None,
    ) -> None:
        event = {
            "eventId": str(uuid.uuid4()),
            "eventType": "NotificationSent" if status == "SENT" else "NotificationFailed",
            "eventVersion": 1,
            "occurredAt": datetime.now(UTC).isoformat(),
            "correlationId": request.correlation_id,
            "source": "notifications-service",
            "data": {
                "notificationId": str(request.notification_id),
                "userId": str(request.user_id) if request.user_id else None,
                "channel": request.channel.value,
                "to": request.to,
                "templateKey": request.template_key,
                "status": status,
                "attempts": attempts,
                "error": error,
            },
        }
        try:
            await self._publisher.publish(self._settings.kafka_topic_out, event)
        except Exception as exc:  # noqa: BLE001
            log.error("publish_notification_event_failed", error=str(exc))


def _backoff_delay(attempt: int, *, base: float, cap: float) -> float:
    """Exponential backoff with full jitter capped at ``cap`` seconds."""
    delay = min(cap, base * (2 ** (attempt - 1)))
    return random.uniform(0, delay)  # noqa: S311 — jitter, not crypto


def notification_to_request(notification: Notification, context: dict[str, Any]) -> DispatchRequest:
    """Convenience constructor used by tests and handlers."""
    return DispatchRequest(
        notification_id=notification.id,
        channel=Channel(notification.channel),
        to=notification.to,
        subject=notification.subject,
        template_key=notification.template_key,
        context=context,
        user_id=notification.user_id,
    )


__all__ = [
    "DispatchRequest",
    "Dispatcher",
    "EventPublisher",
    "notification_to_request",
]
