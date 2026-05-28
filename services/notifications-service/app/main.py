"""FastAPI application factory and lifespan wiring."""
from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

from fastapi import FastAPI

from app.api.routes import api_router, router
from app.config import Settings, get_settings
from app.consumers.kafka_consumer import KafkaEventConsumer
from app.consumers.publisher import KafkaEventPublisher, NullEventPublisher
from app.db.session import Database
from app.delivery.dispatcher import Dispatcher, EventPublisher
from app.delivery.email import SMTPEmailSender
from app.delivery.sms import StubSMSSender
from app.delivery.webhook import HttpWebhookSender
from app.observability.logging import configure_logging, get_logger
from app.observability.otel import configure_tracing, instrument_fastapi


@dataclass
class Container:
    """Service-scoped singletons used across the consumer + REST surface."""

    settings: Settings
    database: Database
    dispatcher: Dispatcher
    publisher: EventPublisher
    consumer: KafkaEventConsumer | None
    ready: bool = False


def build_container(settings: Settings | None = None) -> Container:
    settings = settings or get_settings()
    database = Database(settings)

    publisher: EventPublisher = (
        NullEventPublisher() if settings.testing else KafkaEventPublisher(settings)
    )

    dispatcher = Dispatcher(
        settings=settings,
        database=database,
        email_sender=SMTPEmailSender(
            settings.smtp_host, settings.smtp_port, use_tls=settings.smtp_use_tls
        ),
        sms_sender=StubSMSSender(),
        webhook_sender=HttpWebhookSender(settings.webhook_signing_secret),
        publisher=publisher,
    )

    consumer = (
        None
        if settings.testing
        else KafkaEventConsumer(settings=settings, database=database, dispatcher=dispatcher)
    )

    return Container(
        settings=settings,
        database=database,
        dispatcher=dispatcher,
        publisher=publisher,
        consumer=consumer,
    )


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    configure_logging(settings.log_level)
    configure_tracing(settings)
    log = get_logger("startup")

    container = build_container(settings)
    app.state.container = container
    app.state.database = container.database

    if isinstance(container.publisher, KafkaEventPublisher):
        try:
            await container.publisher.start()
        except Exception as exc:  # noqa: BLE001
            log.error("publisher_start_failed", error=str(exc))

    if container.consumer is not None:
        try:
            await container.consumer.start()
        except Exception as exc:  # noqa: BLE001
            log.error("consumer_start_failed", error=str(exc))

    container.ready = True
    log.info("notifications_service_started")
    try:
        yield
    finally:
        container.ready = False
        if container.consumer is not None:
            await container.consumer.stop()
        if isinstance(container.publisher, KafkaEventPublisher):
            await container.publisher.stop()
        await container.database.dispose()
        log.info("notifications_service_stopped")


def create_app(settings: Settings | None = None) -> FastAPI:
    """Application factory used by Uvicorn and tests."""
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    configure_tracing(settings)

    app = FastAPI(
        title="Notifications Service API",
        version="1.0.0",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
    )
    app.include_router(router)
    app.include_router(api_router)
    if not settings.testing:
        instrument_fastapi(app)
    return app


app = create_app()
