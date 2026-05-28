"""Handler mapping tests (mocked repo + dispatcher)."""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any

from app.consumers.handlers import HandlerContext, handle_envelope
from app.domain.enums import Channel


@dataclass
class _Notif:
    id: uuid.UUID = field(default_factory=uuid.uuid4)
    user_id: uuid.UUID | None = None


class _Repo:
    def __init__(self) -> None:
        self.created: list[dict[str, Any]] = []
        self._seen: set[uuid.UUID] = set()

    async def create_pending(self, **kwargs: Any) -> _Notif | None:
        src = kwargs.get("source_event_id")
        if src is not None and src in self._seen:
            return None
        if src is not None:
            self._seen.add(src)
        self.created.append(kwargs)
        return _Notif(user_id=kwargs.get("user_id"))


class _Session:
    def __init__(self, repo: _Repo) -> None:
        self.repo = repo

    async def commit(self) -> None:
        return None


class _Db:
    def __init__(self) -> None:
        self.repo = _Repo()

    def session(self) -> Any:
        repo = self.repo

        class _Ctx:
            async def __aenter__(self_inner) -> _Session:  # noqa: N805
                return _Session(repo)

            async def __aexit__(self_inner, *_: object) -> None:  # noqa: N805
                return None

        return _Ctx()


class _Dispatcher:
    async def dispatch(self, *_: Any, **__: Any) -> bool:  # pragma: no cover
        return True


async def _run(envelope: dict[str, Any], topic: str, monkeypatch: Any) -> tuple[list[Any], _Db]:
    from app.consumers import handlers

    db = _Db()

    # Replace NotificationRepository in the handlers module with our fake.
    class _FakeRepo:
        def __init__(self, session: _Session) -> None:
            self._repo = session.repo

        async def create_pending(self, **kw: Any) -> _Notif | None:
            return await self._repo.create_pending(**kw)

    monkeypatch.setattr(handlers, "NotificationRepository", _FakeRepo)

    ctx = HandlerContext(database=db, dispatcher=_Dispatcher())  # type: ignore[arg-type]
    requests = await handle_envelope(ctx, envelope, topic=topic)
    return requests, db


def _envelope(event_type: str, data: dict[str, Any]) -> dict[str, Any]:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "eventVersion": 1,
        "occurredAt": "2024-01-01T00:00:00Z",
        "correlationId": "corr-1",
        "source": "test",
        "data": data,
    }


async def test_user_registered_creates_welcome_email(monkeypatch: Any) -> None:
    env = _envelope(
        "UserRegistered",
        {"userId": str(uuid.uuid4()), "email": "a@b.c", "firstName": "Ada"},
    )
    requests, db = await _run(env, "user-events", monkeypatch)
    assert len(requests) == 1
    assert requests[0].channel is Channel.EMAIL
    assert requests[0].template_key == "email/welcome.html"
    assert requests[0].to == "a@b.c"
    assert db.repo.created[0]["channel"] is Channel.EMAIL


async def test_transfer_completed_creates_email_and_sms(monkeypatch: Any) -> None:
    env = _envelope(
        "TransferCompleted",
        {
            "transferId": str(uuid.uuid4()),
            "sourceAccountId": str(uuid.uuid4()),
            "destinationAccountId": str(uuid.uuid4()),
            "amount": 12345,
            "currency": "USD",
            "status": "COMPLETED",
            "email": "x@y.z",
            "phone": "+15551234567",
        },
    )
    requests, _ = await _run(env, "transaction-events", monkeypatch)
    channels = {r.channel for r in requests}
    assert channels == {Channel.EMAIL, Channel.SMS}


async def test_transfer_failed_emits_email_only(monkeypatch: Any) -> None:
    env = _envelope(
        "TransferFailed",
        {
            "transferId": str(uuid.uuid4()),
            "sourceAccountId": str(uuid.uuid4()),
            "destinationAccountId": str(uuid.uuid4()),
            "amount": 100,
            "currency": "USD",
            "status": "FAILED",
            "failureReason": "INSUFFICIENT_FUNDS",
            "email": "x@y.z",
        },
    )
    requests, _ = await _run(env, "transaction-events", monkeypatch)
    assert len(requests) == 1
    assert requests[0].channel is Channel.EMAIL
    assert requests[0].template_key == "email/transfer_failed.html"


async def test_duplicate_event_is_idempotent(monkeypatch: Any) -> None:
    env = _envelope("UserRegistered", {"userId": str(uuid.uuid4()), "email": "a@b.c"})
    requests1, db = await _run(env, "user-events", monkeypatch)
    # Second handle of same envelope -> create_pending returns None
    from app.consumers.handlers import HandlerContext, handle_envelope

    ctx = HandlerContext(database=db, dispatcher=_Dispatcher())  # type: ignore[arg-type]
    requests2 = await handle_envelope(ctx, env, topic="user-events")
    assert len(requests1) == 1
    assert requests2 == []


async def test_unknown_event_type_is_ignored(monkeypatch: Any) -> None:
    env = _envelope("Something.Unknown", {})
    requests, db = await _run(env, "account-events", monkeypatch)
    assert requests == []
    assert db.repo.created == []
