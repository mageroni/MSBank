"""add user+created_at index for notifications

Revision ID: 0002_add_user_created_at_index
Revises: 0001_create_notifications
Create Date: 2024-10-02 00:00:00.000000
"""
from __future__ import annotations

from alembic import op

revision = "0002_add_user_created_at_index"
down_revision = "0001_create_notifications"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Keep this migration id for backward compatibility with existing DB state.
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_notifications_user_created_at "
        "ON notifications (user_id, created_at DESC)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_notifications_user_created_at")
