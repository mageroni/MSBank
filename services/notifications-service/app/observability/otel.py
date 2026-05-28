"""OpenTelemetry tracer initialisation."""
from __future__ import annotations

from typing import TYPE_CHECKING

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from app.config import Settings

if TYPE_CHECKING:
    from fastapi import FastAPI


_INITIALISED = False


def configure_tracing(settings: Settings) -> None:
    """Configure a global OTel tracer provider exporting via OTLP/gRPC."""
    global _INITIALISED  # noqa: PLW0603 — process-wide singleton
    if _INITIALISED:
        return
    resource = Resource.create(
        {
            "service.name": settings.service_name,
            "service.version": "1.0.0",
        }
    )
    provider = TracerProvider(resource=resource)
    if settings.otel_exporter_otlp_endpoint and not settings.testing:
        exporter = OTLPSpanExporter(endpoint=settings.otel_exporter_otlp_endpoint, insecure=True)
        provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    _INITIALISED = True


def instrument_fastapi(app: FastAPI) -> None:
    """Attach the FastAPI OTel instrumentation."""
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

    FastAPIInstrumentor.instrument_app(app)


def get_tracer(name: str = "notifications") -> trace.Tracer:
    return trace.get_tracer(name)
