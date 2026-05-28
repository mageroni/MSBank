"""add composite index on (user_id, created_at DESC)

Revision ID: 0002_add_user_created_at_index
Revises: 0001_create_notifications
Create Date: 2026-05-28 08:00:00.000000

The core list_by_user query filters by user_id and always orders by
created_at DESC with a LIMIT.  The existing single-column ix_notifications_user_id
index forces Postgres to first scan all rows for the user and then perform an
in-memory sort.  A composite index on (user_id, created_at DESC) lets Postgres
satisfy both the WHERE clause and the ORDER BY from the index, eliminating the
sort step entirely.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002_add_user_created_at_index"
down_revision = "0001_create_notifications"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Composite index covers the common query:
    #   WHERE user_id = ? [AND channel = ?] [AND status = ?]
    #   ORDER BY created_at DESC LIMIT ?
    # Postgres can use the leading user_id column for equality filtering and
    # walk the index in reverse-created_at order, avoiding a separate sort.
    op.create_index(
        "ix_notifications_user_id_created_at",
        "notifications",
        ["user_id", sa.text("created_at DESC")],
    )
    # The old single-column user_id index is now redundant: the composite index
    # is a superset and will be chosen for any query that previously used it.
    op.drop_index("ix_notifications_user_id", table_name="notifications")


def downgrade() -> None:
    op.create_index("ix_notifications_user_id", "notifications", ["user_id"])
    op.drop_index("ix_notifications_user_id_created_at", table_name="notifications")
