"""drop redundant single-column user_id index

Revision ID: 0003_drop_redundant_user_id_index
Revises: 0002_add_user_created_at_index
Create Date: 2026-10-09 00:00:00.000000
"""
from __future__ import annotations

from alembic import op

revision = "0003_drop_redundant_user_id_index"
down_revision = "0002_add_user_created_at_index"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.get_context().autocommit_block():
        op.execute("DROP INDEX CONCURRENTLY IF EXISTS ix_notifications_user_id")


def downgrade() -> None:
    with op.get_context().autocommit_block():
        op.execute(
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_user_id "
            "ON notifications (user_id)"
        )
