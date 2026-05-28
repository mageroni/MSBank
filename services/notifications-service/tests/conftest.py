"""Shared pytest fixtures."""
from __future__ import annotations

import os

# Ensure tests don't try to talk to real Kafka / OTel.
os.environ.setdefault("NOTIFICATIONS_TESTING", "1")
os.environ.setdefault("NOTIFICATIONS_DB_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
os.environ.setdefault("SMTP_HOST", "localhost")
os.environ.setdefault("SMTP_PORT", "1025")
os.environ.setdefault("JWT_ISSUER", "https://auth.test.local")
os.environ.setdefault("JWT_AUDIENCE", "msbank")
