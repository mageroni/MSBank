package saga

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/require"

	"github.com/msbank/transactions-service/internal/accounts"
	"github.com/msbank/transactions-service/internal/idem"
	"github.com/msbank/transactions-service/internal/ledger"
)

// fakeAccounts is an in-memory accounts.Client used to drive the saga state
// machine through all branches without a network.
type fakeAccounts struct {
	mu              sync.Mutex
	failReserve     bool
	failCommit      bool
	failDeposit     bool
	failRelease     bool
	failReverse     bool // applied to the second deposit call (the source reversal)
	depositCalls    int
	releaseCalls    int
	commitCalls     int
	reservationID   uuid.UUID
}

func (f *fakeAccounts) Reserve(_ context.Context, _ uuid.UUID, _ accounts.ReserveRequest) (*accounts.ReserveResponse, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failReserve {
		return nil, errors.New("reserve failed")
	}
	f.reservationID = uuid.New()
	return &accounts.ReserveResponse{ReservationID: f.reservationID}, nil
}

func (f *fakeAccounts) Commit(_ context.Context, _, _ uuid.UUID, _ string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.commitCalls++
	if f.failCommit {
		return errors.New("commit failed")
	}
	return nil
}

func (f *fakeAccounts) Release(_ context.Context, _, _ uuid.UUID, _ string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.releaseCalls++
	if f.failRelease {
		return errors.New("release failed")
	}
	return nil
}

func (f *fakeAccounts) Deposit(_ context.Context, _ uuid.UUID, _ accounts.DepositRequest) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.depositCalls++
	// first deposit is credit destination; subsequent is reverse-debit
	if f.depositCalls == 1 && f.failDeposit {
		return errors.New("credit failed")
	}
	if f.depositCalls >= 2 && f.failReverse {
		return errors.New("reverse failed")
	}
	return nil
}

func newTestRepo() *fakeRepo { return &fakeRepo{transfers: map[uuid.UUID]*ledger.Transfer{}} }

// fakeRepo implements just the subset of ledger.Repo methods the orchestrator
// uses. It mirrors all writes in memory.
type fakeRepo struct {
	mu        sync.Mutex
	transfers map[uuid.UUID]*ledger.Transfer
	steps     []ledger.SagaStep
	outbox    []*ledger.OutboxEvent
}

func (r *fakeRepo) put(t *ledger.Transfer) { r.transfers[t.ID] = t }

// ----- methods called via the *ledger.Repo concrete type are not interface
// methods, so we instead wrap by injecting a minimal interface in tests. For
// simplicity, this test uses the real *ledger.Repo not at all and instead
// drives the orchestrator state-by-state via a tiny adapter (see below).

// stepRecorder is the minimal repo interface the test adapter exposes.
type adapterRepo struct{ fr *fakeRepo }

func (a *adapterRepo) GetTransfer(_ context.Context, id uuid.UUID) (*ledger.Transfer, error) {
	a.fr.mu.Lock()
	defer a.fr.mu.Unlock()
	t, ok := a.fr.transfers[id]
	if !ok {
		return nil, ledger.ErrNotFound
	}
	cp := *t
	return &cp, nil
}
func (a *adapterRepo) UpdateTransferStatus(_ context.Context, id uuid.UUID, status, reason string, resID *uuid.UUID, completed bool) error {
	a.fr.mu.Lock()
	defer a.fr.mu.Unlock()
	t := a.fr.transfers[id]
	t.Status = status
	if reason != "" {
		t.FailureReason = reason
	}
	if resID != nil {
		t.ReservationID = resID
	}
	_ = completed
	return nil
}
func (a *adapterRepo) RecordStep(_ context.Context, id uuid.UUID, step, status string, details map[string]any) error {
	a.fr.mu.Lock()
	defer a.fr.mu.Unlock()
	a.fr.steps = append(a.fr.steps, ledger.SagaStep{TransferID: id, Step: step, Status: status, Details: details})
	return nil
}
func (a *adapterRepo) ListSteps(_ context.Context, id uuid.UUID) ([]ledger.SagaStep, error) {
	a.fr.mu.Lock()
	defer a.fr.mu.Unlock()
	out := []ledger.SagaStep{}
	for _, s := range a.fr.steps {
		if s.TransferID == id {
			out = append(out, s)
		}
	}
	return out, nil
}
func (a *adapterRepo) EnqueueOutbox(_ context.Context, e *ledger.OutboxEvent) error {
	a.fr.mu.Lock()
	defer a.fr.mu.Unlock()
	a.fr.outbox = append(a.fr.outbox, e)
	return nil
}
func (a *adapterRepo) FindResumable(_ context.Context, _ int) ([]*ledger.Transfer, error) {
	return nil, nil
}

// newTestOrchestrator builds an orchestrator with the in-memory adapters via
// the small abstractions exposed for testing.
func newTestOrchestrator(t *testing.T, accts accounts.Client) (*Orchestrator, *fakeRepo) {
	t.Helper()
	fr := newTestRepo()
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError}))
	metric := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "test_saga_steps"}, []string{"step", "status"})
	terminal := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "test_transfers"}, []string{"status"})
	repo := newTestableRepo(&adapterRepo{fr: fr})
	o := &Orchestrator{
		repo:     repo,
		clients:  accts,
		idem:     idem.NewStore(nil),
		logger:   logger,
		topic:    "transaction-events",
		metric:   metric,
		terminal: terminal,
	}
	return o, fr
}

func TestSagaHappyPath(t *testing.T) {
	accts := &fakeAccounts{}
	orch, fr := newTestOrchestrator(t, accts)

	id := uuid.New()
	fr.put(&ledger.Transfer{
		ID: id, IdempotencyKey: uuid.New(),
		SourceAccountID: uuid.New(), DestinationAccountID: uuid.New(),
		Amount: 1000, Currency: "USD", Status: ledger.StatusPending,
	})

	orch.Execute(context.Background(), id)

	require.Equal(t, ledger.StatusCompleted, fr.transfers[id].Status)
	require.Equal(t, 1, accts.commitCalls)
	require.Equal(t, 1, accts.depositCalls)
	require.Equal(t, 0, accts.releaseCalls)
	require.Contains(t, lastOutboxTypes(fr), "TransferCompleted")
}

func TestSagaReserveFailure(t *testing.T) {
	accts := &fakeAccounts{failReserve: true}
	orch, fr := newTestOrchestrator(t, accts)
	id := uuid.New()
	fr.put(baseTransfer(id))

	orch.Execute(context.Background(), id)

	require.Equal(t, ledger.StatusFailed, fr.transfers[id].Status)
	require.Contains(t, lastOutboxTypes(fr), "TransferFailed")
}

func TestSagaCommitFailureReleasesReservation(t *testing.T) {
	accts := &fakeAccounts{failCommit: true}
	orch, fr := newTestOrchestrator(t, accts)
	id := uuid.New()
	fr.put(baseTransfer(id))

	orch.Execute(context.Background(), id)

	require.Equal(t, ledger.StatusCompensated, fr.transfers[id].Status)
	require.Equal(t, 1, accts.releaseCalls, "release should be called once")
	require.Equal(t, 0, accts.depositCalls, "no destination deposit happened")
	require.Contains(t, lastOutboxTypes(fr), "TransferCompensated")
}

func TestSagaCreditFailureReversesDebit(t *testing.T) {
	accts := &fakeAccounts{failDeposit: true}
	orch, fr := newTestOrchestrator(t, accts)
	id := uuid.New()
	fr.put(baseTransfer(id))

	orch.Execute(context.Background(), id)

	require.Equal(t, ledger.StatusCompensated, fr.transfers[id].Status)
	require.Equal(t, 1, accts.commitCalls, "source was debited")
	require.GreaterOrEqual(t, accts.depositCalls, 2, "must include the reversal deposit")
	require.Equal(t, 0, accts.releaseCalls, "reservation already committed; release is not used")
}

func baseTransfer(id uuid.UUID) *ledger.Transfer {
	return &ledger.Transfer{
		ID: id, IdempotencyKey: uuid.New(),
		SourceAccountID: uuid.New(), DestinationAccountID: uuid.New(),
		Amount: 500, Currency: "USD", Status: ledger.StatusPending,
	}
}

func lastOutboxTypes(fr *fakeRepo) []string {
	out := make([]string, 0, len(fr.outbox))
	for _, o := range fr.outbox {
		out = append(out, o.EventType)
	}
	return out
}
