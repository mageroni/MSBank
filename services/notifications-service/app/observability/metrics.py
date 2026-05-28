"""Prometheus metrics."""
from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram

REGISTRY = CollectorRegistry(auto_describe=True)

events_consumed_total = Counter(
    "notifications_events_consumed_total",
    "Number of Kafka events consumed.",
    labelnames=("topic", "event_type"),
    registry=REGISTRY,
)

events_dispatched_total = Counter(
    "notifications_events_dispatched_total",
    "Number of notifications successfully dispatched.",
    labelnames=("channel", "template"),
    registry=REGISTRY,
)

dispatch_attempt_total = Counter(
    "notifications_dispatch_attempt_total",
    "Total dispatch attempts (including retries).",
    labelnames=("channel", "outcome"),
    registry=REGISTRY,
)

dispatch_latency_seconds = Histogram(
    "notifications_dispatch_latency_seconds",
    "Time to dispatch a notification end-to-end.",
    labelnames=("channel",),
    registry=REGISTRY,
)

dead_letter_total = Counter(
    "notifications_dead_letter_total",
    "Number of notifications that ended in DEAD_LETTERED.",
    labelnames=("channel", "template"),
    registry=REGISTRY,
)

inflight_dispatches = Gauge(
    "notifications_inflight_dispatches",
    "Notifications currently being dispatched.",
    registry=REGISTRY,
)
