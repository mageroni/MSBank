"""REST API routers."""
from __future__ import annotations

import uuid
from typing import cast

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.responses import Response

from app.api.deps import CurrentUser, get_current_user, get_db, require_admin
from app.api.schemas import (
    NotificationOut,
    TestNotificationAccepted,
    TestNotificationRequest,
)
from app.db.repositories import NotificationRepository
from app.delivery.dispatcher import Dispatcher, DispatchRequest
from app.domain.enums import Channel, NotificationStatus
from app.observability.metrics import REGISTRY

router = APIRouter()
api_router = APIRouter(prefix="/api/v1")


# ----- Liveness / readiness / metrics ------------------------------------


@router.get("/healthz", include_in_schema=False)
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/readyz", include_in_schema=False)
async def readyz(request: Request) -> dict[str, str]:
    container = getattr(request.app.state, "container", None)
    if container is None or not container.ready:
        raise HTTPException(status_code=503, detail="not ready")
    return {"status": "ready"}


@router.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    payload = generate_latest(REGISTRY)
    return Response(content=payload, media_type=CONTENT_TYPE_LATEST)


# ----- Notifications resource --------------------------------------------


@api_router.get("/notifications", response_model=list[NotificationOut])
async def list_notifications(
    channel: Channel | None = Query(default=None),
    status_: NotificationStatus | None = Query(default=None, alias="status"),
    limit: int = Query(default=50, ge=1, le=500),
    user: CurrentUser = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> list[NotificationOut]:
    repo = NotificationRepository(session)
    rows = await repo.list_for_user(user.user_id, channel=channel, status=status_, limit=limit)
    return [NotificationOut.model_validate(row) for row in rows]


@api_router.get("/notifications/{notification_id}", response_model=NotificationOut)
async def get_notification(
    notification_id: uuid.UUID,
    user: CurrentUser = Depends(get_current_user),
    session: AsyncSession = Depends(get_db),
) -> NotificationOut:
    repo = NotificationRepository(session)
    notification = await repo.get(notification_id)
    if notification is None or notification.user_id != user.user_id:
        raise HTTPException(status_code=404, detail="Notification not found")
    return NotificationOut.model_validate(notification)


@api_router.post(
    "/notifications/test",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=TestNotificationAccepted,
)
async def send_test_notification(
    body: TestNotificationRequest,
    request: Request,
    user: CurrentUser = Depends(require_admin),
    session: AsyncSession = Depends(get_db),
) -> TestNotificationAccepted:
    dispatcher = cast(Dispatcher, request.app.state.container.dispatcher)
    repo = NotificationRepository(session)
    notification = await repo.create_pending(
        user_id=user.user_id,
        channel=body.channel,
        to=body.to,
        subject=str(body.context.get("subject", "Test notification")),
        template_key=body.templateKey,
        source_event_id=None,
        payload={"context": body.context, "via": "rest"},
    )
    await session.commit()
    assert notification is not None  # source_event_id is None → never deduped
    await dispatcher.dispatch(
        DispatchRequest(
            notification_id=notification.id,
            channel=body.channel,
            to=body.to,
            subject=notification.subject,
            template_key=body.templateKey,
            context=body.context,
            user_id=user.user_id,
        )
    )
    return TestNotificationAccepted(
        notificationId=notification.id, status=NotificationStatus.PENDING
    )
