"""Repositories for the notifications domain."""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import select, text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.enums import Channel, NotificationStatus
from app.domain.models import Notification


class NotificationRepository:
    """Data access for ``notifications`` rows."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create_pending(
        self,
        *,
        user_id: uuid.UUID | None,
        channel: Channel,
        to: str,
        subject: str | None,
        template_key: str,
        source_event_id: uuid.UUID | None,
        payload: dict[str, Any],
    ) -> Notification | None:
        """Insert a PENDING notification, idempotent on ``source_event_id``.

        Returns the row if newly inserted; ``None`` if a row with the same
        ``source_event_id`` already exists (duplicate event).
        """
        notification = Notification(
            id=uuid.uuid4(),
            user_id=user_id,
            channel=channel.value,
            to=to,
            subject=subject,
            template_key=template_key,
            status=NotificationStatus.PENDING.value,
            attempts=0,
            source_event_id=source_event_id,
            payload=payload,
        )
        if source_event_id is None:
            self._session.add(notification)
            await self._session.flush()
            return notification

        stmt = (
            pg_insert(Notification)
            .values(
                id=notification.id,
                user_id=notification.user_id,
                channel=notification.channel,
                to=notification.to,
                subject=notification.subject,
                template_key=notification.template_key,
                status=notification.status,
                attempts=0,
                source_event_id=notification.source_event_id,
                payload=notification.payload,
            )
            .on_conflict_do_nothing(
                index_elements=["source_event_id"],
                index_where=text("source_event_id IS NOT NULL"),
            )
            .returning(Notification.id)
        )
        result = await self._session.execute(stmt)
        inserted_id = result.scalar_one_or_none()
        if inserted_id is None:
            return None
        return await self.get(inserted_id)

    async def get(self, notification_id: uuid.UUID) -> Notification | None:
        return await self._session.get(Notification, notification_id)

    async def list_for_user(
        self,
        user_id: uuid.UUID,
        *,
        channel: Channel | None = None,
        status: NotificationStatus | None = None,
        limit: int = 50,
    ) -> list[Notification]:
        stmt = select(Notification).where(Notification.user_id == user_id)
        if channel is not None:
            stmt = stmt.where(Notification.channel == channel.value)
        if status is not None:
            stmt = stmt.where(Notification.status == status.value)
        stmt = stmt.order_by(Notification.created_at.desc()).limit(limit)
        result = await self._session.execute(stmt)
        return list(result.scalars().all())

    async def mark_sent(self, notification_id: uuid.UUID, *, attempts: int) -> None:
        notification = await self._session.get(Notification, notification_id)
        if notification is None:
            return
        notification.status = NotificationStatus.SENT.value
        notification.attempts = attempts
        notification.sent_at = datetime.utcnow()
        notification.last_error = None
        await self._session.flush()

    async def mark_failure(
        self,
        notification_id: uuid.UUID,
        *,
        attempts: int,
        error: str,
        dead_lettered: bool,
    ) -> None:
        notification = await self._session.get(Notification, notification_id)
        if notification is None:
            return
        notification.attempts = attempts
        notification.last_error = error[:4000]
        notification.status = (
            NotificationStatus.DEAD_LETTERED.value
            if dead_lettered
            else NotificationStatus.FAILED.value
        )
        await self._session.flush()
