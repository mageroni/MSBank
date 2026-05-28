"""Structured JSON logging with OpenTelemetry trace correlation."""
from __future__ import annotations

import logging
import sys
from collections.abc import Mapping, MutableMapping
from typing import Any, cast

import structlog
from opentelemetry import trace


def _add_otel_context(
    _logger: logging.Logger,
    _method: str,
    event_dict: MutableMapping[str, Any],
) -> Mapping[str, Any]:
    """Inject OTel trace/span IDs into log records when an active span exists."""
    span = trace.get_current_span()
    ctx = span.get_span_context() if span else None
    if ctx is not None and ctx.is_valid:
        event_dict.setdefault("trace_id", f"{ctx.trace_id:032x}")
        event_dict.setdefault("span_id", f"{ctx.span_id:016x}")
    return event_dict


def configure_logging(level: str = "info") -> None:
    """Configure stdlib + structlog to emit structured JSON to stdout."""
    log_level = getattr(logging, level.upper(), logging.INFO)
    logging.basicConfig(
        format="%(message)s", stream=sys.stdout, level=log_level, force=True
    )

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            _add_otel_context,
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(log_level),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str | None = None) -> structlog.stdlib.BoundLogger:
    """Return a bound structlog logger."""
    logger = structlog.get_logger(name) if name else structlog.get_logger()
    return cast(structlog.stdlib.BoundLogger, logger)
