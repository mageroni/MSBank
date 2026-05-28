"""Domain enumerations."""
from __future__ import annotations

from enum import StrEnum


class Channel(StrEnum):
    EMAIL = "EMAIL"
    SMS = "SMS"
    WEBHOOK = "WEBHOOK"


class NotificationStatus(StrEnum):
    PENDING = "PENDING"
    SENT = "SENT"
    FAILED = "FAILED"
    DEAD_LETTERED = "DEAD_LETTERED"


class Role(StrEnum):
    USER = "USER"
    ADMIN = "ADMIN"
