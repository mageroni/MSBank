"""Pydantic schemas used by the REST API."""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from app.domain.enums import Channel, NotificationStatus


class NotificationOut(BaseModel):
    """Response model for the ``Notification`` resource (matches OpenAPI)."""

    model_config = ConfigDict(from_attributes=True, populate_by_name=True)

    id: uuid.UUID
    userId: uuid.UUID | None = Field(default=None, alias="user_id")
    channel: Channel
    to: str
    subject: str | None = None
    templateKey: str = Field(alias="template_key")
    status: NotificationStatus
    attempts: int
    lastError: str | None = Field(default=None, alias="last_error")
    createdAt: datetime = Field(alias="created_at")
    sentAt: datetime | None = Field(default=None, alias="sent_at")


class TestNotificationRequest(BaseModel):
    """Body for ``POST /api/v1/notifications/test``."""

    channel: Channel
    to: str
    templateKey: str
    context: dict[str, Any] = Field(default_factory=dict)


class TestNotificationAccepted(BaseModel):
    notificationId: uuid.UUID
    status: NotificationStatus
