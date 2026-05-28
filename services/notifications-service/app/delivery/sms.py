"""SMS delivery stub.

The demo platform doesn't integrate with a real SMS gateway; this module logs
the outbound message and exposes the same interface as the email sender so the
dispatcher can treat it uniformly.
"""
from __future__ import annotations

from typing import Protocol

from app.observability.logging import get_logger

log = get_logger(__name__)


class SMSSender(Protocol):
    async def send(self, *, to: str, body: str) -> None: ...


class StubSMSSender:
    """No-op SMS sender used for demo/testing."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    async def send(self, *, to: str, body: str) -> None:
        self.sent.append((to, body))
        log.info("sms_sent", to=to, body_preview=body[:80])
