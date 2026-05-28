"""Email delivery via aiosmtplib."""
from __future__ import annotations

from email.message import EmailMessage
from typing import Protocol

import aiosmtplib

from app.observability.logging import get_logger

log = get_logger(__name__)


class EmailSender(Protocol):
    async def send(self, *, to: str, subject: str, html_body: str, from_addr: str) -> None: ...


class SMTPEmailSender:
    """Sends multipart/alternative emails through an async SMTP connection."""

    def __init__(self, host: str, port: int, *, use_tls: bool = False) -> None:
        self._host = host
        self._port = port
        self._use_tls = use_tls

    async def send(self, *, to: str, subject: str, html_body: str, from_addr: str) -> None:
        message = EmailMessage()
        message["From"] = from_addr
        message["To"] = to
        message["Subject"] = subject
        message.set_content(_strip_html(html_body))
        message.add_alternative(html_body, subtype="html")

        await aiosmtplib.send(
            message,
            hostname=self._host,
            port=self._port,
            use_tls=self._use_tls,
            timeout=10,
        )
        log.info("email_sent", to=to, subject=subject)


def _strip_html(html: str) -> str:
    """Very small HTML-to-text fallback used for the multipart text leg."""
    import re

    text = re.sub(r"<\s*br\s*/?\s*>", "\n", html, flags=re.IGNORECASE)
    text = re.sub(r"</p\s*>", "\n\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    return text.strip()
