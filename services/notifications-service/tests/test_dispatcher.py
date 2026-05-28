"""Dispatcher unit tests: retry semantics, DLQ, and event emission."""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any

import pytest

from app.config import Settings
from app.consumers.publisher import NullEventPublisher
from app.delivery.dispatcher import Dispatcher, DispatchRequest
from app.domain.enums import Channel, NotificationStatus


@dataclass
class _FakeNotification:
    id: uuid.UUID
    status: str = NotificationStatus.PENDING.value
    attempts: int = 0
    last_error: str | None = None
    sent_at: Any = None


class _FakeRepo:
    def __init__(self, store: dict[uuid.UUID, _FakeNotification]) -> None:
        self._store = store

    async def mark_sent(self, nid: uuid.UUID, *, attempts: int) -> None:
        n = self._store[nid]
        n.status = NotificationStatus.SENT.value
        n.attempts = attempts

    async def mark_failure(
        self, nid: uuid.UUID, *, attempts: int, error: str, dead_lettered: bool
    ) -> None:
        n = self._store[nid]
        n.attempts = attempts
        n.last_error = error
        n.status = (
            NotificationStatus.DEAD_LETTERED.value
            if dead_lettered
            else NotificationStatus.FAILED.value
        )


class _FakeSession:
    def __init__(self, repo: _FakeRepo) -> None:
        self.repo = repo

    async def commit(self) -> None:
        return None


class _FakeDb:
    def __init__(self, store: dict[uuid.UUID, _FakeNotification]) -> None:
        self._store = store

    def session(self) -> Any:
        repo = _FakeRepo(self._store)

        class _Ctx:
            async def __aenter__(self_inner) -> _FakeSession:  # noqa: N805
                return _FakeSession(repo)

            async def __aexit__(self_inner, *_: object) -> None:  # noqa: N805
                return None

        return _Ctx()


@dataclass
class _CountingEmail:
    fail_times: int
    calls: list[dict[str, str]] = field(default_factory=list)

    async def send(self, *, to: str, subject: str, html_body: str, from_addr: str) -> None:
        self.calls.append({"to": to, "subject": subject})
        if len(self.calls) <= self.fail_times:
            raise ConnectionError("smtp down")


class _StubSms:
    async def send(self, *, to: str, body: str) -> None:
        return None


class _StubWebhook:
    async def send(self, *, to: str, payload: dict[str, Any]) -> None:
        return None


@pytest.fixture
def _settings() -> Settings:
    return Settings(
        delivery_max_attempts=5,
        delivery_backoff_base=0.0,
        delivery_backoff_max=0.0,
        testing=True,
    )


# Override repo creation inside dispatcher via monkeypatch
@pytest.fixture(autouse=True)
def _patch_repo(monkeypatch: pytest.MonkeyPatch) -> None:
    from app.delivery import dispatcher as dispatcher_module

    class _DirectRepo:
        def __init__(self, session: _FakeSession) -> None:
            self._repo = session.repo

        async def mark_sent(self, nid: uuid.UUID, *, attempts: int) -> None:
            await self._repo.mark_sent(nid, attempts=attempts)

        async def mark_failure(
            self, nid: uuid.UUID, *, attempts: int, error: str, dead_lettered: bool
        ) -> None:
            await self._repo.mark_failure(
                nid, attempts=attempts, error=error, dead_lettered=dead_lettered
            )

    monkeypatch.setattr(dispatcher_module, "NotificationRepository", _DirectRepo)


async def test_dispatch_retries_then_succeeds(_settings: Settings) -> None:
    nid = uuid.uuid4()
    store = {nid: _FakeNotification(id=nid)}
    publisher = NullEventPublisher()
    dispatcher = Dispatcher(
        settings=_settings,
        database=_FakeDb(store),  # type: ignore[arg-type]
        email_sender=_CountingEmail(fail_times=2),
        sms_sender=_StubSms(),
        webhook_sender=_StubWebhook(),
        publisher=publisher,
    )

    ok = await dispatcher.dispatch(
        DispatchRequest(
            notification_id=nid,
            channel=Channel.EMAIL,
            to="ada@example.com",
            subject="Hello",
            template_key="email/welcome.html",
            context={"first_name": "Ada", "subject": "Hello"},
        )
    )
    assert ok is True
    assert store[nid].status == NotificationStatus.SENT.value
    assert store[nid].attempts == 3
    assert any(ev[1]["eventType"] == "NotificationSent" for ev in publisher.published)


async def test_dispatch_dead_letters_after_max_attempts(_settings: Settings) -> None:
    nid = uuid.uuid4()
    store = {nid: _FakeNotification(id=nid)}
    publisher = NullEventPublisher()
    dispatcher = Dispatcher(
        settings=_settings,
        database=_FakeDb(store),  # type: ignore[arg-type]
        email_sender=_CountingEmail(fail_times=99),
        sms_sender=_StubSms(),
        webhook_sender=_StubWebhook(),
        publisher=publisher,
    )

    ok = await dispatcher.dispatch(
        DispatchRequest(
            notification_id=nid,
            channel=Channel.EMAIL,
            to="ada@example.com",
            subject="Hello",
            template_key="email/welcome.html",
            context={"first_name": "Ada", "subject": "Hello"},
        )
    )
    assert ok is False
    assert store[nid].status == NotificationStatus.DEAD_LETTERED.value
    assert store[nid].attempts == _settings.delivery_max_attempts
    assert store[nid].last_error and "ConnectionError" in store[nid].last_error
    assert publisher.published[-1][1]["eventType"] == "NotificationFailed"
