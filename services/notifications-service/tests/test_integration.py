"""End-to-end integration test using testcontainers (Postgres + Redpanda) and aiosmtpd.

Skipped automatically when Docker isn't available.
"""
from __future__ import annotations

import asyncio
import json
import os
import uuid
from typing import Any

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_INTEGRATION") != "1",
    reason="set RUN_INTEGRATION=1 to run (requires Docker for testcontainers).",
)


@pytest.mark.asyncio
async def test_transfer_completed_end_to_end() -> None:
    from aiokafka import AIOKafkaProducer
    from aiosmtpd.controller import Controller
    from sqlalchemy.ext.asyncio import create_async_engine
    from testcontainers.kafka import RedpandaContainer
    from testcontainers.postgres import PostgresContainer

    received_messages: list[bytes] = []

    class _Handler:
        async def handle_DATA(self, server: Any, session: Any, envelope: Any) -> str:  # noqa: N802
            received_messages.append(envelope.content)
            return "250 Message accepted for delivery"

    smtp = Controller(_Handler(), hostname="127.0.0.1", port=0)
    smtp.start()

    with PostgresContainer("postgres:16-alpine") as pg, RedpandaContainer() as rp:
        db_url = pg.get_connection_url().replace("psycopg2", "asyncpg")
        bootstrap = rp.get_bootstrap_server()

        os.environ.update(
            {
                "NOTIFICATIONS_DB_URL": db_url,
                "KAFKA_BOOTSTRAP_SERVERS": bootstrap,
                "SMTP_HOST": "127.0.0.1",
                "SMTP_PORT": str(smtp.port),
                "NOTIFICATIONS_TESTING": "0",
            }
        )

        # Re-load settings cache.
        from app.config import get_settings

        get_settings.cache_clear()  # type: ignore[attr-defined]
        settings = get_settings()

        # Apply migrations.
        from alembic import command
        from alembic.config import Config

        cfg = Config("alembic.ini")
        cfg.set_main_option("sqlalchemy.url", db_url)
        command.upgrade(cfg, "head")

        from app.main import build_container

        container = build_container(settings)
        await container.publisher.start()  # type: ignore[union-attr]
        assert container.consumer is not None
        await container.consumer.start()

        producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
        await producer.start()
        try:
            envelope = {
                "eventId": str(uuid.uuid4()),
                "eventType": "TransferCompleted",
                "eventVersion": 1,
                "occurredAt": "2024-01-01T00:00:00Z",
                "correlationId": "int-1",
                "source": "tests",
                "data": {
                    "transferId": str(uuid.uuid4()),
                    "sourceAccountId": str(uuid.uuid4()),
                    "destinationAccountId": str(uuid.uuid4()),
                    "amount": 1000,
                    "currency": "USD",
                    "status": "COMPLETED",
                    "email": "alice@example.com",
                    "phone": "+15551234567",
                },
            }
            await producer.send_and_wait(
                "transaction-events", json.dumps(envelope).encode("utf-8")
            )
        finally:
            await producer.stop()

        # Poll for the email to arrive at the in-process SMTP.
        for _ in range(60):
            if received_messages:
                break
            await asyncio.sleep(0.5)

        await container.consumer.stop()
        await container.publisher.stop()  # type: ignore[union-attr]

        engine = create_async_engine(db_url)
        async with engine.begin() as conn:
            rows = (
                await conn.exec_driver_sql(
                    "SELECT status, channel FROM notifications ORDER BY created_at"
                )
            ).all()
        await engine.dispose()

        assert received_messages, "expected at least one email to be delivered"
        assert any(r[0] == "SENT" and r[1] == "EMAIL" for r in rows)

    smtp.stop()
