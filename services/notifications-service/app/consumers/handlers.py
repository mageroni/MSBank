"""Event handlers: map domain events to notification dispatches.

Each handler receives the validated envelope, persists a PENDING ``Notification``
row (idempotently keyed by ``eventId``) and submits a ``DispatchRequest`` to the
dispatcher.

Mapping
-------
+-------------------+--------------+---------------------------+-------------+
| eventType         | channel(s)   | template_key              | recipient   |
+-------------------+--------------+---------------------------+-------------+
| UserRegistered    | EMAIL        | email/welcome.html        | data.email  |
| AccountOpened     | EMAIL        | email/account_opened.html | (resolved)  |
| TransferCompleted | EMAIL + SMS  | email/transfer_completed  | (resolved)  |
| TransferFailed    | EMAIL        | email/transfer_failed     | (resolved)  |
+-------------------+--------------+---------------------------+-------------+
"""
from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.db.repositories import NotificationRepository
from app.db.session import Database
from app.delivery.dispatcher import Dispatcher, DispatchRequest
from app.domain.enums import Channel
from app.observability.logging import get_logger
from app.observability.metrics import events_consumed_total

log = get_logger(__name__)


class Envelope(BaseModel):
    """CloudEvents-inspired envelope shared across all topics."""

    model_config = ConfigDict(extra="allow")

    eventId: uuid.UUID
    eventType: str
    eventVersion: int = 1
    occurredAt: str
    correlationId: str | None = None
    causationId: str | None = None
    source: str
    data: dict[str, Any] = Field(default_factory=dict)


HandlerResult = list[DispatchRequest]
HandlerFn = Callable[["HandlerContext", Envelope], Awaitable[HandlerResult]]


class HandlerContext:
    """Lightweight container the handler functions interact with."""

    def __init__(self, *, database: Database, dispatcher: Dispatcher) -> None:
        self.database = database
        self.dispatcher = dispatcher


# ---------------------------------------------------------------------------
# Handlers
# ---------------------------------------------------------------------------


async def _handle_user_registered(ctx: HandlerContext, env: Envelope) -> HandlerResult:
    email = str(env.data.get("email") or "")
    user_id = _opt_uuid(env.data.get("userId"))
    if not email:
        log.warning("user_registered_missing_email", event_id=str(env.eventId))
        return []

    template_key = "email/welcome.html"
    context = {
        "subject": "Welcome to Microservice Bank",
        "first_name": env.data.get("firstName") or "there",
        "user_id": str(user_id) if user_id else None,
    }
    notification = await _persist_pending(
        ctx,
        user_id=user_id,
        channel=Channel.EMAIL,
        to=email,
        subject=context["subject"],
        template_key=template_key,
        source_event_id=env.eventId,
        payload={"context": context, "event": env.model_dump(mode="json")},
    )
    if notification is None:
        return []
    return [
        DispatchRequest(
            notification_id=notification.id,
            channel=Channel.EMAIL,
            to=email,
            subject=str(context["subject"]),
            template_key=template_key,
            context=context,
            correlation_id=env.correlationId,
            user_id=user_id,
        )
    ]


async def _handle_account_opened(ctx: HandlerContext, env: Envelope) -> HandlerResult:
    customer_id = _opt_uuid(env.data.get("customerId"))
    # In production we'd resolve the customer's email via the auth-service.
    # For the demo we look it up in the envelope or fall back to a synthesised
    # address based on the customer id.
    email = str(env.data.get("email") or f"customer-{customer_id}@msbank.local")
    template_key = "email/account_opened.html"
    context = {
        "subject": "Your new account is ready",
        "account_id": env.data.get("accountId"),
        "account_type": env.data.get("accountType", "CHECKING"),
        "currency": env.data.get("currency", "USD"),
    }
    notification = await _persist_pending(
        ctx,
        user_id=customer_id,
        channel=Channel.EMAIL,
        to=email,
        subject=context["subject"],
        template_key=template_key,
        source_event_id=env.eventId,
        payload={"context": context, "event": env.model_dump(mode="json")},
    )
    if notification is None:
        return []
    return [
        DispatchRequest(
            notification_id=notification.id,
            channel=Channel.EMAIL,
            to=email,
            subject=str(context["subject"]),
            template_key=template_key,
            context=context,
            correlation_id=env.correlationId,
            user_id=customer_id,
        )
    ]


async def _handle_transfer_completed(ctx: HandlerContext, env: Envelope) -> HandlerResult:
    return await _handle_transfer(ctx, env, status="COMPLETED")


async def _handle_transfer_failed(ctx: HandlerContext, env: Envelope) -> HandlerResult:
    return await _handle_transfer(ctx, env, status="FAILED")


async def _handle_transfer(
    ctx: HandlerContext, env: Envelope, *, status: str
) -> HandlerResult:
    user_id = _opt_uuid(env.data.get("userId") or env.data.get("sourceCustomerId"))
    email = str(env.data.get("email") or f"customer-{user_id}@msbank.local")
    phone = str(env.data.get("phone") or "+10000000000")

    if status == "COMPLETED":
        email_template = "email/transfer_completed.html"
        sms_template = "sms/transfer_completed.txt"
        subject = "Your transfer is complete"
    else:
        email_template = "email/transfer_failed.html"
        sms_template = "sms/transfer_failed.txt"
        subject = "Your transfer could not be completed"

    context = {
        "subject": subject,
        "transfer_id": env.data.get("transferId"),
        "amount": _format_amount(env.data.get("amount"), env.data.get("currency")),
        "currency": env.data.get("currency", "USD"),
        "source_account": env.data.get("sourceAccountId"),
        "destination_account": env.data.get("destinationAccountId"),
        "failure_reason": env.data.get("failureReason"),
        "status": status,
    }

    requests: HandlerResult = []
    email_notif = await _persist_pending(
        ctx,
        user_id=user_id,
        channel=Channel.EMAIL,
        to=email,
        subject=subject,
        template_key=email_template,
        source_event_id=env.eventId,
        payload={"context": context, "event": env.model_dump(mode="json")},
    )
    if email_notif is not None:
        requests.append(
            DispatchRequest(
                notification_id=email_notif.id,
                channel=Channel.EMAIL,
                to=email,
                subject=subject,
                template_key=email_template,
                context=context,
                correlation_id=env.correlationId,
                user_id=user_id,
            )
        )

    # SMS only on success — matches the requirement and avoids double-noise on failure.
    if status == "COMPLETED":
        # Use a derived id so we don't collide with the email row on the unique index.
        sms_event_id = uuid.uuid5(env.eventId, "sms")
        sms_notif = await _persist_pending(
            ctx,
            user_id=user_id,
            channel=Channel.SMS,
            to=phone,
            subject=None,
            template_key=sms_template,
            source_event_id=sms_event_id,
            payload={"context": context, "event": env.model_dump(mode="json")},
        )
        if sms_notif is not None:
            requests.append(
                DispatchRequest(
                    notification_id=sms_notif.id,
                    channel=Channel.SMS,
                    to=phone,
                    subject=None,
                    template_key=sms_template,
                    context=context,
                    correlation_id=env.correlationId,
                    user_id=user_id,
                )
            )
    return requests


# ---------------------------------------------------------------------------
# Dispatch table & entrypoint
# ---------------------------------------------------------------------------


HANDLERS: dict[str, HandlerFn] = {
    "UserRegistered": _handle_user_registered,
    "AccountOpened": _handle_account_opened,
    "TransferCompleted": _handle_transfer_completed,
    "TransferFailed": _handle_transfer_failed,
}


async def handle_envelope(
    ctx: HandlerContext, raw: dict[str, Any], *, topic: str
) -> HandlerResult:
    """Validate the envelope, route to the matching handler, return dispatches.

    Returns an empty list when no handler is registered for the event type or
    when the row was a duplicate (idempotent replay).
    """
    try:
        envelope = Envelope.model_validate(raw)
    except ValidationError as exc:
        log.warning("invalid_envelope", topic=topic, error=str(exc))
        return []

    events_consumed_total.labels(topic=topic, event_type=envelope.eventType).inc()
    handler = HANDLERS.get(envelope.eventType)
    if handler is None:
        log.debug("event_type_ignored", event_type=envelope.eventType, topic=topic)
        return []
    return await handler(ctx, envelope)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def _persist_pending(
    ctx: HandlerContext,
    *,
    user_id: uuid.UUID | None,
    channel: Channel,
    to: str,
    subject: str | None,
    template_key: str,
    source_event_id: uuid.UUID,
    payload: dict[str, Any],
) -> Any:
    async with ctx.database.session() as session:
        repo = NotificationRepository(session)
        notification = await repo.create_pending(
            user_id=user_id,
            channel=channel,
            to=to,
            subject=subject,
            template_key=template_key,
            source_event_id=source_event_id,
            payload=payload,
        )
        await session.commit()
        return notification


def _opt_uuid(value: Any) -> uuid.UUID | None:
    if value is None:
        return None
    try:
        return uuid.UUID(str(value))
    except (TypeError, ValueError):
        return None


def _format_amount(amount: Any, currency: Any) -> str:
    """Format integer minor units into a human-friendly string."""
    try:
        minor = int(amount)
    except (TypeError, ValueError):
        return f"{amount} {currency or ''}".strip()
    major = minor / 100
    return f"{major:,.2f} {currency or ''}".strip()
