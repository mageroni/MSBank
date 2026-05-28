"""Outbound Kafka producer for notification-events."""
from __future__ import annotations

import json
from typing import Any

from aiokafka import AIOKafkaProducer

from app.config import Settings
from app.observability.logging import get_logger

log = get_logger(__name__)


class KafkaEventPublisher:
    """Thin async wrapper around aiokafka producer used by the dispatcher."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._producer: AIOKafkaProducer | None = None

    async def start(self) -> None:
        if self._producer is not None:
            return
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            acks="all",
            enable_idempotence=True,
        )
        await self._producer.start()
        log.info("kafka_producer_started")

    async def stop(self) -> None:
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None
            log.info("kafka_producer_stopped")

    async def publish(self, topic: str, event: dict[str, Any]) -> None:
        if self._producer is None:
            raise RuntimeError("Producer not started")
        key = event.get("data", {}).get("notificationId")
        await self._producer.send_and_wait(
            topic,
            value=event,
            key=key.encode("utf-8") if isinstance(key, str) else None,
        )


class NullEventPublisher:
    """In-memory publisher used when Kafka is unavailable (tests/local)."""

    def __init__(self) -> None:
        self.published: list[tuple[str, dict[str, Any]]] = []

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    async def publish(self, topic: str, event: dict[str, Any]) -> None:
        self.published.append((topic, event))
        log.debug("null_publisher_received", topic=topic, event_type=event.get("eventType"))
