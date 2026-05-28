"""Runtime configuration loaded from environment variables."""
from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings sourced from environment variables.

    Field names map to env vars via ``env`` aliases so the same .env file used
    by the rest of the platform works without modification.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    service_name: str = Field(default="notifications-service")
    service_port: int = Field(default=8084)
    log_level: str = Field(default="info", alias="LOG_LEVEL")

    notifications_db_url: str = Field(
        default="postgresql+asyncpg://msbank:msbank_dev_only@localhost:5432/notifications",
        alias="NOTIFICATIONS_DB_URL",
    )

    kafka_bootstrap_servers: str = Field(
        default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS"
    )
    kafka_consumer_group: str = Field(
        default="notifications-service", alias="KAFKA_CONSUMER_GROUP"
    )
    kafka_topics_in: tuple[str, ...] = Field(
        default=("user-events", "account-events", "transaction-events")
    )
    kafka_topic_out: str = Field(default="notification-events")

    smtp_host: str = Field(default="localhost", alias="SMTP_HOST")
    smtp_port: int = Field(default=1025, alias="SMTP_PORT")
    smtp_from: str = Field(default="no-reply@msbank.local", alias="SMTP_FROM")
    smtp_use_tls: bool = Field(default=False, alias="SMTP_USE_TLS")

    jwt_issuer: str = Field(default="https://auth.msbank.local", alias="JWT_ISSUER")
    jwt_audience: str = Field(default="msbank", alias="JWT_AUDIENCE")
    jwt_jwks_uri: str | None = Field(default=None, alias="JWT_JWKS_URI")
    jwt_jwks_ttl_seconds: int = Field(default=300)
    jwt_algorithms: tuple[str, ...] = Field(default=("RS256",))
    # Optional static public key (PEM) for local dev / tests when JWKS is unavailable.
    jwt_public_key_pem: str | None = Field(default=None, alias="JWT_PUBLIC_KEY_PEM")

    redis_url: str = Field(default="redis://localhost:6379/0", alias="REDIS_URL")

    otel_exporter_otlp_endpoint: str | None = Field(
        default=None, alias="OTEL_EXPORTER_OTLP_ENDPOINT"
    )

    delivery_max_attempts: int = Field(default=5)
    delivery_backoff_base: float = Field(default=0.5)
    delivery_backoff_max: float = Field(default=8.0)

    webhook_signing_secret: str = Field(
        default="dev-webhook-secret", alias="WEBHOOK_SIGNING_SECRET"
    )

    # Used by tests to disable real network calls.
    testing: bool = Field(default=False, alias="NOTIFICATIONS_TESTING")


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return cached settings."""
    return Settings()
