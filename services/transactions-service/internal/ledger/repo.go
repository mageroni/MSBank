package ledger

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Status values for transfers.
const (
	StatusPending      = "PENDING"
	StatusReserved     = "RESERVED"
	StatusDebited      = "DEBITED"
	StatusCredited     = "CREDITED"
	StatusCompleted    = "COMPLETED"
	StatusFailed       = "FAILED"
	StatusCompensating = "COMPENSATING"
	StatusCompensated  = "COMPENSATED"
)

// Saga step names and statuses.
const (
	StepReserveSource       = "RESERVE_SOURCE"
	StepDebitSource         = "DEBIT_SOURCE"
	StepCreditDestination   = "CREDIT_DESTINATION"
	StepReleaseReservation  = "RELEASE_RESERVATION"
	StepReverseDebit        = "REVERSE_DEBIT"
	StepEmitNotification    = "EMIT_NOTIFICATION"

	StepStatusStarted     = "STARTED"
	StepStatusSucceeded   = "SUCCEEDED"
	StepStatusFailed      = "FAILED"
	StepStatusCompensated = "COMPENSATED"
)

type Transfer struct {
	ID                   uuid.UUID
	IdempotencyKey       uuid.UUID
	SourceAccountID      uuid.UUID
	DestinationAccountID uuid.UUID
	Amount               int64
	Currency             string
	Reference            string
	Status               string
	FailureReason        string
	ReservationID        *uuid.UUID
	CorrelationID        string
	CreatedAt            time.Time
	UpdatedAt            time.Time
	CompletedAt          *time.Time
}

type SagaStep struct {
	ID         uuid.UUID
	TransferID uuid.UUID
	Step       string
	Status     string
	Details    map[string]any
	OccurredAt time.Time
}

type OutboxEvent struct {
	ID          uuid.UUID
	AggregateID uuid.UUID
	Topic       string
	EventType   string
	Envelope    []byte
	CreatedAt   time.Time
}

// ErrNotFound is returned by lookups when no row matches.
var ErrNotFound = errors.New("not found")

type Repo struct {
	pool *pgxpool.Pool
}

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }

func (r *Repo) Pool() *pgxpool.Pool { return r.pool }

// CreateTransferWithIdem inserts a transfer + idempotency key atomically using
// SERIALIZABLE isolation. If a transfer with the same idempotency key already
// exists, it is returned unchanged and `existed` is true.
func (r *Repo) CreateTransferWithIdem(ctx context.Context, t *Transfer, outbox *OutboxEvent) (*Transfer, bool, error) {
	var existed bool
	var out *Transfer
	err := r.withTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable}, func(tx pgx.Tx) error {
		var existingID uuid.UUID
		err := tx.QueryRow(ctx,
			`SELECT transfer_id FROM idempotency_keys WHERE key=$1`, t.IdempotencyKey,
		).Scan(&existingID)
		if err == nil {
			existed = true
			got, err := getTransferTx(ctx, tx, existingID)
			if err != nil {
				return err
			}
			out = got
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		_, err = tx.Exec(ctx, `
			INSERT INTO transfers (id, idempotency_key, source_account_id, destination_account_id,
				amount, currency, reference, status, correlation_id)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
			t.ID, t.IdempotencyKey, t.SourceAccountID, t.DestinationAccountID,
			t.Amount, t.Currency, t.Reference, t.Status, t.CorrelationID)
		if err != nil {
			return err
		}
		_, err = tx.Exec(ctx,
			`INSERT INTO idempotency_keys (key, transfer_id) VALUES ($1,$2)`,
			t.IdempotencyKey, t.ID)
		if err != nil {
			return err
		}
		if outbox != nil {
			if err := insertOutboxTx(ctx, tx, outbox); err != nil {
				return err
			}
		}
		out = t
		return nil
	})
	if err != nil {
		return nil, false, err
	}
	return out, existed, nil
}

func (r *Repo) withTx(ctx context.Context, opts pgx.TxOptions, fn func(pgx.Tx) error) error {
	tx, err := r.pool.BeginTx(ctx, opts)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback(ctx)
		return err
	}
	return tx.Commit(ctx)
}

// WithTx exposes a transactional helper so other packages can compose writes.
func (r *Repo) WithTx(ctx context.Context, fn func(pgx.Tx) error) error {
	return r.withTx(ctx, pgx.TxOptions{}, fn)
}

func getTransferTx(ctx context.Context, q pgx.Tx, id uuid.UUID) (*Transfer, error) {
	row := q.QueryRow(ctx, selectTransferSQL+` WHERE id=$1`, id)
	return scanTransfer(row)
}

const selectTransferSQL = `SELECT id, idempotency_key, source_account_id, destination_account_id,
	amount, currency, COALESCE(reference,''), status, COALESCE(failure_reason,''),
	reservation_id, COALESCE(correlation_id,''), created_at, updated_at, completed_at
	FROM transfers`

type scanner interface {
	Scan(dest ...any) error
}

func scanTransfer(s scanner) (*Transfer, error) {
	var t Transfer
	var resID sql.NullString
	var completed sql.NullTime
	err := s.Scan(&t.ID, &t.IdempotencyKey, &t.SourceAccountID, &t.DestinationAccountID,
		&t.Amount, &t.Currency, &t.Reference, &t.Status, &t.FailureReason,
		&resID, &t.CorrelationID, &t.CreatedAt, &t.UpdatedAt, &completed)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if resID.Valid {
		u, perr := uuid.Parse(resID.String)
		if perr == nil {
			t.ReservationID = &u
		}
	}
	if completed.Valid {
		t.CompletedAt = &completed.Time
	}
	return &t, nil
}

func (r *Repo) GetTransfer(ctx context.Context, id uuid.UUID) (*Transfer, error) {
	row := r.pool.QueryRow(ctx, selectTransferSQL+` WHERE id=$1`, id)
	return scanTransfer(row)
}

type ListFilter struct {
	AccountID *uuid.UUID
	Status    string
	From      *time.Time
	To        *time.Time
	Limit     int
}

func (r *Repo) ListTransfers(ctx context.Context, f ListFilter) ([]*Transfer, error) {
	q := selectTransferSQL + " WHERE 1=1"
	args := []any{}
	i := 1
	if f.AccountID != nil {
		q += fmt.Sprintf(" AND (source_account_id=$%d OR destination_account_id=$%d)", i, i)
		args = append(args, *f.AccountID)
		i++
	}
	if f.Status != "" {
		q += fmt.Sprintf(" AND status=$%d", i)
		args = append(args, f.Status)
		i++
	}
	if f.From != nil {
		q += fmt.Sprintf(" AND created_at >= $%d", i)
		args = append(args, *f.From)
		i++
	}
	if f.To != nil {
		q += fmt.Sprintf(" AND created_at <= $%d", i)
		args = append(args, *f.To)
		i++
	}
	limit := f.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	q += fmt.Sprintf(" ORDER BY created_at DESC LIMIT %d", limit)
	rows, err := r.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Transfer
	for rows.Next() {
		t, err := scanTransfer(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (r *Repo) UpdateTransferStatus(ctx context.Context, id uuid.UUID, status, failureReason string,
	reservationID *uuid.UUID, completed bool) error {
	completedExpr := "completed_at"
	if completed {
		completedExpr = "now()"
	}
	_, err := r.pool.Exec(ctx, fmt.Sprintf(`
		UPDATE transfers
		   SET status=$1, failure_reason=NULLIF($2,''), reservation_id=COALESCE($3, reservation_id),
		       updated_at=now(), completed_at=%s
		 WHERE id=$4`, completedExpr),
		status, failureReason, reservationID, id)
	return err
}

func (r *Repo) RecordStep(ctx context.Context, transferID uuid.UUID, step, status string, details map[string]any) error {
	var detailsJSON []byte
	if details != nil {
		detailsJSON, _ = json.Marshal(details)
	}
	_, err := r.pool.Exec(ctx,
		`INSERT INTO saga_steps (transfer_id, step, status, details) VALUES ($1,$2,$3,$4)`,
		transferID, step, status, detailsJSON)
	return err
}

func (r *Repo) ListSteps(ctx context.Context, transferID uuid.UUID) ([]SagaStep, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT id, transfer_id, step, status, COALESCE(details,'{}'::jsonb), occurred_at
		   FROM saga_steps WHERE transfer_id=$1 ORDER BY occurred_at ASC`, transferID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SagaStep
	for rows.Next() {
		var s SagaStep
		var raw []byte
		if err := rows.Scan(&s.ID, &s.TransferID, &s.Step, &s.Status, &raw, &s.OccurredAt); err != nil {
			return nil, err
		}
		if len(raw) > 0 {
			_ = json.Unmarshal(raw, &s.Details)
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// FindResumable returns transfers in non-terminal states for the saga recovery loop.
func (r *Repo) FindResumable(ctx context.Context, limit int) ([]*Transfer, error) {
	rows, err := r.pool.Query(ctx, selectTransferSQL+`
		 WHERE status IN ($1,$2,$3,$4,$5)
		 ORDER BY updated_at ASC LIMIT $6`,
		StatusPending, StatusReserved, StatusDebited, StatusCredited, StatusCompensating, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Transfer
	for rows.Next() {
		t, err := scanTransfer(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (r *Repo) EnqueueOutbox(ctx context.Context, e *OutboxEvent) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO outbox_events (aggregate_id, topic, event_type, envelope)
		VALUES ($1,$2,$3,$4)`, e.AggregateID, e.Topic, e.EventType, e.Envelope)
	return err
}

func insertOutboxTx(ctx context.Context, tx pgx.Tx, e *OutboxEvent) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO outbox_events (aggregate_id, topic, event_type, envelope)
		VALUES ($1,$2,$3,$4)`, e.AggregateID, e.Topic, e.EventType, e.Envelope)
	return err
}

// PendingOutbox returns unpublished outbox events.
func (r *Repo) PendingOutbox(ctx context.Context, limit int) ([]OutboxEvent, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, aggregate_id, topic, event_type, envelope, created_at
		  FROM outbox_events WHERE published_at IS NULL
		  ORDER BY created_at ASC LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []OutboxEvent
	for rows.Next() {
		var e OutboxEvent
		if err := rows.Scan(&e.ID, &e.AggregateID, &e.Topic, &e.EventType, &e.Envelope, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (r *Repo) MarkOutboxPublished(ctx context.Context, id uuid.UUID) error {
	_, err := r.pool.Exec(ctx, `UPDATE outbox_events SET published_at=now() WHERE id=$1`, id)
	return err
}
