"""create notifications table

Revision ID: 0001_create_notifications
Revises:
Create Date: 2024-10-01 00:00:00.000000
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001_create_notifications"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "notifications",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("user_id", postgresql.UUID(as_uuid=True), nullable=True, index=True),
        sa.Column("channel", sa.String(length=16), nullable=False),
        sa.Column("to", sa.String(length=320), nullable=False),
        sa.Column("subject", sa.String(length=512), nullable=True),
        sa.Column("template_key", sa.String(length=128), nullable=False),
        sa.Column("status", sa.String(length=20), nullable=False, server_default="PENDING"),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("last_error", sa.Text(), nullable=True),
        sa.Column("source_event_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
        sa.Column("sent_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "payload",
            postgresql.JSONB(astext_type=sa.Text()),
            nullable=False,
            server_default=sa.text("'{}'::jsonb"),
        ),
    )
    op.create_index(
        "uq_notifications_source_event_id",
        "notifications",
        ["source_event_id"],
        unique=True,
        postgresql_where=sa.text("source_event_id IS NOT NULL"),
    )
    op.create_index("ix_notifications_status", "notifications", ["status"])
    op.create_index("ix_notifications_channel", "notifications", ["channel"])


def downgrade() -> None:
    op.drop_index("ix_notifications_channel", table_name="notifications")
    op.drop_index("ix_notifications_status", table_name="notifications")
    op.drop_index("uq_notifications_source_event_id", table_name="notifications")
    op.drop_table("notifications")
