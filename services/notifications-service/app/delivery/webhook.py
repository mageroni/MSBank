"""Outbound webhook delivery with HMAC signature."""
from __future__ import annotations

import hashlib
import hmac
import json
from typing import Any, Protocol

import httpx

from app.observability.logging import get_logger

log = get_logger(__name__)


class WebhookSender(Protocol):
    async def send(self, *, to: str, payload: dict[str, Any]) -> None: ...


class HttpWebhookSender:
    """Signs the JSON body with HMAC-SHA256 and POSTs it to ``to``."""

    def __init__(self, signing_secret: str, *, timeout: float = 10.0) -> None:
        self._secret = signing_secret.encode("utf-8")
        self._timeout = timeout

    async def send(self, *, to: str, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        signature = hmac.new(self._secret, body, hashlib.sha256).hexdigest()
        headers = {
            "content-type": "application/json",
            "x-msbank-signature": f"sha256={signature}",
        }
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(to, content=body, headers=headers)
            response.raise_for_status()
        log.info("webhook_sent", to=to, status=response.status_code)
