"""Multi-topic Kafka consumer with manual offset commit per processed event."""
from __future__ import annotations

import asyncio
import json
from typing import Any

from aiokafka import AIOKafkaConsumer

from app.config import Settings
from app.consumers.handlers import HandlerContext, handle_envelope
from app.db.session import Database
from app.delivery.dispatcher import Dispatcher
from app.observability.logging import get_logger
from app.observability.otel import get_tracer

log = get_logger(__name__)
tracer = get_tracer("notifications.consumer")


class KafkaEventConsumer:
    """Consume domain events from all configured topics and dispatch them."""

    def __init__(
        self,
        *,
        settings: Settings,
        database: Database,
        dispatcher: Dispatcher,
    ) -> None:
        self._settings = settings
        self._database = database
        self._dispatcher = dispatcher
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()
        self._ctx = HandlerContext(database=database, dispatcher=dispatcher)

    async def start(self) -> None:
        if self._task is not None:
            return
        self._consumer = AIOKafkaConsumer(
            *self._settings.kafka_topics_in,
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            group_id=self._settings.kafka_consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            value_deserializer=lambda v: v,
        )
        await self._consumer.start()
        log.info(
            "kafka_consumer_started",
            topics=list(self._settings.kafka_topics_in),
            group=self._settings.kafka_consumer_group,
        )
        self._task = asyncio.create_task(self._run(), name="kafka-consumer")

    async def stop(self) -> None:
        self._stop_event.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception) as exc:  # noqa: BLE001
                log.debug("consumer_task_stopped", error=str(exc))
            self._task = None
        if self._consumer is not None:
            await self._consumer.stop()
            self._consumer = None
        log.info("kafka_consumer_stopped")

    async def _run(self) -> None:
        assert self._consumer is not None
        try:
            async for msg in self._consumer:
                if self._stop_event.is_set():
                    break
                await self._process(msg.topic, msg.value)
                # Commit only AFTER the Notification rows are persisted.
                try:
                    await self._consumer.commit()
                except Exception as exc:  # noqa: BLE001
                    log.error("kafka_commit_failed", error=str(exc))
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.exception("kafka_consumer_loop_error", error=str(exc))

    async def _process(self, topic: str, raw_value: bytes | None) -> None:
        if raw_value is None:
            return
        try:
            payload: dict[str, Any] = json.loads(raw_value)
        except json.JSONDecodeError as exc:
            log.warning("invalid_json", topic=topic, error=str(exc))
            return

        with tracer.start_as_current_span("kafka.consume") as span:
            span.set_attribute("messaging.system", "kafka")
            span.set_attribute("messaging.destination", topic)
            span.set_attribute("messaging.operation", "process")
            try:
                requests = await handle_envelope(self._ctx, payload, topic=topic)
            except Exception as exc:  # noqa: BLE001
                log.exception("handler_error", topic=topic, error=str(exc))
                return

        # Fire-and-await dispatches sequentially so that retry sleeps don't block
        # the consumer indefinitely. For higher throughput a worker pool would
        # be used; we keep ordering guarantees within a partition here.
        for request in requests:
            try:
                await self._dispatcher.dispatch(request)
            except Exception as exc:  # noqa: BLE001
                log.exception(
                    "dispatch_unhandled_error",
                    notification_id=str(request.notification_id),
                    error=str(exc),
                )
