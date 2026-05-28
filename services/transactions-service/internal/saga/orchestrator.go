// Package saga implements the transfer saga orchestrator. The orchestrator is
// process-based but resumable: every step writes to saga_steps before and
// after the side-effect, so a recovery loop on startup can resume any
// transfer left in a non-terminal state.
package saga

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/cenkalti/backoff/v4"
	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/msbank/transactions-service/internal/accounts"
	"github.com/msbank/transactions-service/internal/events"
	"github.com/msbank/transactions-service/internal/idem"
	"github.com/msbank/transactions-service/internal/ledger"
)

const (
	source                       = "transactions-service"
	eventTransferInitiated       = "TransferInitiated"
	eventTransferCompleted       = "TransferCompleted"
	eventTransferFailed          = "TransferFailed"
	eventTransferCompensated     = "TransferCompensated"
)

// repository is the persistence surface the orchestrator depends on. Using
// an interface here keeps the saga state machine unit-testable with an
// in-memory fake while still letting production wire in *ledger.Repo.
type repository interface {
	GetTransfer(ctx context.Context, id uuid.UUID) (*ledger.Transfer, error)
	UpdateTransferStatus(ctx context.Context, id uuid.UUID, status, reason string, resID *uuid.UUID, completed bool) error
	RecordStep(ctx context.Context, id uuid.UUID, step, status string, details map[string]any) error
	ListSteps(ctx context.Context, id uuid.UUID) ([]ledger.SagaStep, error)
	EnqueueOutbox(ctx context.Context, e *ledger.OutboxEvent) error
	FindResumable(ctx context.Context, limit int) ([]*ledger.Transfer, error)
}

// newTestableRepo is used in tests to wrap an in-memory fake. Production
// callers pass *ledger.Repo directly.
func newTestableRepo(r repository) repository { return r }

// Orchestrator drives transfer sagas forward.
type Orchestrator struct {
	repo      repository
	clients   accounts.Client
	idem      *idem.Store
	logger    *slog.Logger
	topic     string
	metric    *prometheus.CounterVec // (step,status) — per saga step
	terminal  *prometheus.CounterVec // (status) — final transfer outcome
}

func NewOrchestrator(repo *ledger.Repo, clients accounts.Client, idemStore *idem.Store,
	logger *slog.Logger, topic string, stepMetric, terminalMetric *prometheus.CounterVec) *Orchestrator {
	return &Orchestrator{
		repo:     &repoAdapter{Repo: repo},
		clients:  clients,
		idem:     idemStore,
		logger:   logger,
		topic:    topic,
		metric:   stepMetric,
		terminal: terminalMetric,
	}
}

// repoAdapter promotes *ledger.Repo to the orchestrator's repository
// interface. The methods already match by name; we just need a thin shim so
// FindResumable's signature lines up.
type repoAdapter struct{ *ledger.Repo }

// Execute drives a transfer through the full saga. It is safe to call multiple
// times for the same transfer; each step inspects current persisted state and
// only acts when needed.
func (o *Orchestrator) Execute(ctx context.Context, transferID uuid.UUID) {
	logger := o.logger.With("transferId", transferID)
	t, err := o.repo.GetTransfer(ctx, transferID)
	if err != nil {
		logger.Error("execute: load transfer failed", "err", err)
		return
	}

	switch t.Status {
	case ledger.StatusCompleted, ledger.StatusFailed, ledger.StatusCompensated:
		return
	}

	if t.Status == ledger.StatusCompensating {
		o.compensate(ctx, t, t.FailureReason)
		return
	}

	if t.Status == ledger.StatusPending {
		res, err := o.reserveSource(ctx, t)
		if err != nil {
			o.fail(ctx, t, "reserve_failed: "+err.Error())
			return
		}
		t.ReservationID = &res.ReservationID
		t.Status = ledger.StatusReserved
		if err := o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusReserved, "", &res.ReservationID, false); err != nil {
			logger.Error("update RESERVED failed", "err", err)
			return
		}
	}

	if t.Status == ledger.StatusReserved {
		if err := o.debitSource(ctx, t); err != nil {
			o.startCompensation(ctx, t, "debit_failed: "+err.Error())
			return
		}
		t.Status = ledger.StatusDebited
		if err := o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusDebited, "", nil, false); err != nil {
			logger.Error("update DEBITED failed", "err", err)
			return
		}
	}

	if t.Status == ledger.StatusDebited {
		if err := o.creditDestination(ctx, t); err != nil {
			o.startCompensation(ctx, t, "credit_failed: "+err.Error())
			return
		}
		t.Status = ledger.StatusCredited
		if err := o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusCredited, "", nil, false); err != nil {
			logger.Error("update CREDITED failed", "err", err)
			return
		}
	}

	if t.Status == ledger.StatusCredited {
		if err := o.emitCompleted(ctx, t); err != nil {
			logger.Error("emit completion failed", "err", err)
			return
		}
		if err := o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusCompleted, "", nil, true); err != nil {
			logger.Error("update COMPLETED failed", "err", err)
			return
		}
		o.terminal.WithLabelValues(ledger.StatusCompleted).Inc()
	}
}

func (o *Orchestrator) reserveSource(ctx context.Context, t *ledger.Transfer) (*accounts.ReserveResponse, error) {
	step := ledger.StepReserveSource
	if err := o.recordStarted(ctx, t.ID, step); err != nil {
		return nil, err
	}
	idemKey := deriveIdem(t.ID, step)
	if locked, _ := o.idem.Lock(ctx, idemKey); !locked {
		// another worker is handling this step; back off
		return nil, fmt.Errorf("step locked")
	}
	defer func() { _ = o.idem.Unlock(context.Background(), idemKey) }()

	res, err := o.clients.Reserve(ctx, t.SourceAccountID, accounts.ReserveRequest{
		TransactionID:  t.ID,
		Amount:         t.Amount,
		Currency:       t.Currency,
		IdempotencyKey: idemKey,
	})
	if err != nil {
		o.recordFailed(ctx, t.ID, step, err)
		return nil, err
	}
	o.recordSucceeded(ctx, t.ID, step, map[string]any{"reservationId": res.ReservationID})
	return res, nil
}

func (o *Orchestrator) debitSource(ctx context.Context, t *ledger.Transfer) error {
	step := ledger.StepDebitSource
	if t.ReservationID == nil {
		return errors.New("missing reservation id")
	}
	if err := o.recordStarted(ctx, t.ID, step); err != nil {
		return err
	}
	idemKey := deriveIdem(t.ID, step)
	err := o.clients.Commit(ctx, t.SourceAccountID, *t.ReservationID, idemKey)
	if err != nil {
		o.recordFailed(ctx, t.ID, step, err)
		return err
	}
	o.recordSucceeded(ctx, t.ID, step, nil)
	return nil
}

func (o *Orchestrator) creditDestination(ctx context.Context, t *ledger.Transfer) error {
	step := ledger.StepCreditDestination
	if err := o.recordStarted(ctx, t.ID, step); err != nil {
		return err
	}
	idemKey := deriveIdem(t.ID, step)
	err := o.clients.Deposit(ctx, t.DestinationAccountID, accounts.DepositRequest{
		TransactionID:  t.ID,
		Amount:         t.Amount,
		Currency:       t.Currency,
		Description:    t.Reference,
		IdempotencyKey: idemKey,
	})
	if err != nil {
		o.recordFailed(ctx, t.ID, step, err)
		return err
	}
	o.recordSucceeded(ctx, t.ID, step, nil)
	return nil
}

func (o *Orchestrator) emitCompleted(ctx context.Context, t *ledger.Transfer) error {
	step := ledger.StepEmitNotification
	if err := o.recordStarted(ctx, t.ID, step); err != nil {
		return err
	}
	payload := transferPayload(t, ledger.StatusCompleted, "")
	env, err := events.NewEnvelope(eventTransferCompleted, source, t.CorrelationID, payload)
	if err != nil {
		return err
	}
	raw, _ := json.Marshal(env)
	if err := o.repo.EnqueueOutbox(ctx, &ledger.OutboxEvent{
		AggregateID: t.ID, Topic: o.topic, EventType: eventTransferCompleted, Envelope: raw,
	}); err != nil {
		o.recordFailed(ctx, t.ID, step, err)
		return err
	}
	o.recordSucceeded(ctx, t.ID, step, nil)
	return nil
}

func (o *Orchestrator) startCompensation(ctx context.Context, t *ledger.Transfer, reason string) {
	if err := o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusCompensating, reason, nil, false); err != nil {
		o.logger.Error("mark COMPENSATING failed", "err", err)
		return
	}
	t.Status = ledger.StatusCompensating
	t.FailureReason = reason
	o.compensate(ctx, t, reason)
}

func (o *Orchestrator) compensate(ctx context.Context, t *ledger.Transfer, reason string) {
	// Determine which steps actually ran by inspecting saga_steps.
	steps, _ := o.repo.ListSteps(ctx, t.ID)
	debited := stepSucceeded(steps, ledger.StepDebitSource)
	reserved := stepSucceeded(steps, ledger.StepReserveSource)

	if debited {
		// Reverse the debit by depositing back to the source account.
		if err := o.reverseDebit(ctx, t); err != nil {
			o.logger.Error("reverse debit failed", "err", err, "transferId", t.ID)
			o.fail(ctx, t, "reverse_debit_failed: "+err.Error())
			return
		}
	} else if reserved && t.ReservationID != nil {
		if err := o.releaseReservation(ctx, t); err != nil {
			o.logger.Error("release reservation failed", "err", err, "transferId", t.ID)
			o.fail(ctx, t, "release_failed: "+err.Error())
			return
		}
	}

	payload := transferPayload(t, ledger.StatusCompensated, reason)
	env, _ := events.NewEnvelope(eventTransferCompensated, source, t.CorrelationID, payload)
	raw, _ := json.Marshal(env)
	_ = o.repo.EnqueueOutbox(ctx, &ledger.OutboxEvent{
		AggregateID: t.ID, Topic: o.topic, EventType: eventTransferCompensated, Envelope: raw,
	})
	if err := o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusCompensated, reason, nil, true); err != nil {
		o.logger.Error("mark COMPENSATED failed", "err", err)
		return
	}
	o.terminal.WithLabelValues(ledger.StatusCompensated).Inc()
}

func (o *Orchestrator) releaseReservation(ctx context.Context, t *ledger.Transfer) error {
	step := ledger.StepReleaseReservation
	_ = o.recordStarted(ctx, t.ID, step)
	idemKey := deriveIdem(t.ID, step)
	op := func() error {
		return o.clients.Release(ctx, t.SourceAccountID, *t.ReservationID, idemKey)
	}
	bo := backoff.WithContext(backoff.NewExponentialBackOff(), ctx)
	if err := backoff.Retry(op, backoff.WithMaxRetries(bo, 3)); err != nil {
		o.recordFailed(ctx, t.ID, step, err)
		return err
	}
	o.recordCompensated(ctx, t.ID, step)
	return nil
}

func (o *Orchestrator) reverseDebit(ctx context.Context, t *ledger.Transfer) error {
	step := ledger.StepReverseDebit
	_ = o.recordStarted(ctx, t.ID, step)
	idemKey := deriveIdem(t.ID, step)
	op := func() error {
		return o.clients.Deposit(ctx, t.SourceAccountID, accounts.DepositRequest{
			TransactionID:  t.ID,
			Amount:         t.Amount,
			Currency:       t.Currency,
			Description:    "reversal:" + t.ID.String(),
			IdempotencyKey: idemKey,
		})
	}
	bo := backoff.WithContext(backoff.NewExponentialBackOff(), ctx)
	if err := backoff.Retry(op, backoff.WithMaxRetries(bo, 3)); err != nil {
		o.recordFailed(ctx, t.ID, step, err)
		return err
	}
	o.recordCompensated(ctx, t.ID, step)
	return nil
}

func (o *Orchestrator) fail(ctx context.Context, t *ledger.Transfer, reason string) {
	payload := transferPayload(t, ledger.StatusFailed, reason)
	env, _ := events.NewEnvelope(eventTransferFailed, source, t.CorrelationID, payload)
	raw, _ := json.Marshal(env)
	_ = o.repo.EnqueueOutbox(ctx, &ledger.OutboxEvent{
		AggregateID: t.ID, Topic: o.topic, EventType: eventTransferFailed, Envelope: raw,
	})
	_ = o.repo.UpdateTransferStatus(ctx, t.ID, ledger.StatusFailed, reason, nil, true)
	o.terminal.WithLabelValues(ledger.StatusFailed).Inc()
}

func (o *Orchestrator) recordStarted(ctx context.Context, id uuid.UUID, step string) error {
	o.metric.WithLabelValues(step, ledger.StepStatusStarted).Inc()
	return o.repo.RecordStep(ctx, id, step, ledger.StepStatusStarted, nil)
}
func (o *Orchestrator) recordSucceeded(ctx context.Context, id uuid.UUID, step string, details map[string]any) {
	o.metric.WithLabelValues(step, ledger.StepStatusSucceeded).Inc()
	_ = o.repo.RecordStep(ctx, id, step, ledger.StepStatusSucceeded, details)
}
func (o *Orchestrator) recordFailed(ctx context.Context, id uuid.UUID, step string, cause error) {
	o.metric.WithLabelValues(step, ledger.StepStatusFailed).Inc()
	_ = o.repo.RecordStep(ctx, id, step, ledger.StepStatusFailed, map[string]any{"error": cause.Error()})
}
func (o *Orchestrator) recordCompensated(ctx context.Context, id uuid.UUID, step string) {
	o.metric.WithLabelValues(step, ledger.StepStatusCompensated).Inc()
	_ = o.repo.RecordStep(ctx, id, step, ledger.StepStatusCompensated, nil)
}

func stepSucceeded(steps []ledger.SagaStep, name string) bool {
	for _, s := range steps {
		if s.Step == name && s.Status == ledger.StepStatusSucceeded {
			return true
		}
	}
	return false
}

func deriveIdem(transferID uuid.UUID, step string) string {
	return fmt.Sprintf("%s:%s", transferID, step)
}

func transferPayload(t *ledger.Transfer, status, reason string) map[string]any {
	m := map[string]any{
		"transferId":           t.ID,
		"sourceAccountId":      t.SourceAccountID,
		"destinationAccountId": t.DestinationAccountID,
		"amount":               t.Amount,
		"currency":             t.Currency,
		"status":               status,
	}
	if reason != "" {
		m["failureReason"] = reason
	}
	return m
}

// EmitInitiated enqueues a TransferInitiated event in the outbox transactionally
// when called within a tx. The current implementation enqueues post-commit which
// is acceptable because the orchestrator only proceeds once the transfer row is
// committed.
func (o *Orchestrator) EmitInitiated(ctx context.Context, t *ledger.Transfer) ([]byte, error) {
	payload := transferPayload(t, ledger.StatusPending, "")
	env, err := events.NewEnvelope(eventTransferInitiated, source, t.CorrelationID, payload)
	if err != nil {
		return nil, err
	}
	return json.Marshal(env)
}

// Recovery scans for non-terminal sagas and resumes them. Run periodically.
type Recovery struct {
	repo  *ledger.Repo
	orch  *Orchestrator
	log   *slog.Logger
	every time.Duration
}

func NewRecovery(repo *ledger.Repo, orch *Orchestrator, log *slog.Logger, every time.Duration) *Recovery {
	return &Recovery{repo: repo, orch: orch, log: log, every: every}
}

func (r *Recovery) Run(ctx context.Context) {
	r.sweep(ctx)
	t := time.NewTicker(r.every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			r.sweep(ctx)
		}
	}
}

func (r *Recovery) sweep(ctx context.Context) {
	rows, err := r.repo.FindResumable(ctx, 100)
	if err != nil {
		r.log.Warn("recovery: list failed", "err", err)
		return
	}
	for _, t := range rows {
		// Skip transfers that were just touched to avoid stomping in-flight work.
		if time.Since(t.UpdatedAt) < 5*time.Second {
			continue
		}
		r.log.Info("recovering saga", "transferId", t.ID, "status", t.Status)
		r.orch.Execute(ctx, t.ID)
	}
}
