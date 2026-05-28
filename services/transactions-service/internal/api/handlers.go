package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"

	"github.com/msbank/transactions-service/internal/auth"
	"github.com/msbank/transactions-service/internal/ledger"
	"github.com/msbank/transactions-service/internal/saga"
)

type TransferRequest struct {
	SourceAccountID      uuid.UUID `json:"sourceAccountId" validate:"required"`
	DestinationAccountID uuid.UUID `json:"destinationAccountId" validate:"required,nefield=SourceAccountID"`
	Amount               int64     `json:"amount" validate:"required,gt=0"`
	Currency             string    `json:"currency" validate:"required,len=3"`
	Reference            string    `json:"reference" validate:"max=140"`
}

type TransferDTO struct {
	ID                   uuid.UUID  `json:"id"`
	IdempotencyKey       uuid.UUID  `json:"idempotencyKey"`
	SourceAccountID      uuid.UUID  `json:"sourceAccountId"`
	DestinationAccountID uuid.UUID  `json:"destinationAccountId"`
	Amount               int64      `json:"amount"`
	Currency             string     `json:"currency"`
	Reference            string     `json:"reference,omitempty"`
	Status               string     `json:"status"`
	FailureReason        string     `json:"failureReason,omitempty"`
	CreatedAt            time.Time  `json:"createdAt"`
	CompletedAt          *time.Time `json:"completedAt,omitempty"`
}

func toDTO(t *ledger.Transfer) TransferDTO {
	return TransferDTO{
		ID:                   t.ID,
		IdempotencyKey:       t.IdempotencyKey,
		SourceAccountID:      t.SourceAccountID,
		DestinationAccountID: t.DestinationAccountID,
		Amount:               t.Amount,
		Currency:             t.Currency,
		Reference:            t.Reference,
		Status:               t.Status,
		FailureReason:        t.FailureReason,
		CreatedAt:            t.CreatedAt,
		CompletedAt:          t.CompletedAt,
	}
}

type SagaStepDTO struct {
	Step       string         `json:"step"`
	Status     string         `json:"status"`
	OccurredAt time.Time      `json:"occurredAt"`
	Details    map[string]any `json:"details,omitempty"`
}

type Handler struct {
	repo      *ledger.Repo
	orch      Orchestrator
	validator *validator.Validate
}

// Orchestrator is the subset of saga.Orchestrator used by the handler.
type Orchestrator interface {
	Execute(ctx_ ContextLike, id uuid.UUID)
	EmitInitiated(ctx_ ContextLike, t *ledger.Transfer) ([]byte, error)
}

// ContextLike is a tiny alias kept to avoid importing context here twice.
type ContextLike = interface {
	Deadline() (time.Time, bool)
	Done() <-chan struct{}
	Err() error
	Value(any) any
}

// To keep handler decoupled from saga package while still typing it, define a
// minimal adapter.
type sagaAdapter struct{ s *saga.Orchestrator }

func (a *sagaAdapter) Execute(ctx ContextLike, id uuid.UUID) {
	a.s.Execute(asCtx(ctx), id)
}
func (a *sagaAdapter) EmitInitiated(ctx ContextLike, t *ledger.Transfer) ([]byte, error) {
	return a.s.EmitInitiated(asCtx(ctx), t)
}

func NewHandler(repo *ledger.Repo, orch *saga.Orchestrator) *Handler {
	return &Handler{repo: repo, orch: &sagaAdapter{s: orch}, validator: validator.New()}
}

// problem returns an RFC 7807 problem+json response.
func problem(w http.ResponseWriter, status int, title, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"type":   "about:blank",
		"title":  title,
		"status": status,
		"detail": detail,
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func (h *Handler) CreateTransfer(w http.ResponseWriter, r *http.Request) {
	idemHeader := r.Header.Get("Idempotency-Key")
	idemKey, err := uuid.Parse(idemHeader)
	if err != nil {
		problem(w, http.StatusBadRequest, "Invalid Idempotency-Key", "must be uuid")
		return
	}
	var req TransferRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		problem(w, http.StatusBadRequest, "Invalid JSON", err.Error())
		return
	}
	if err := h.validator.Struct(req); err != nil {
		problem(w, http.StatusUnprocessableEntity, "Validation failed", err.Error())
		return
	}
	correlationID := r.Header.Get("X-Correlation-Id")
	if correlationID == "" {
		correlationID = uuid.NewString()
	}
	t := &ledger.Transfer{
		ID:                   uuid.New(),
		IdempotencyKey:       idemKey,
		SourceAccountID:      req.SourceAccountID,
		DestinationAccountID: req.DestinationAccountID,
		Amount:               req.Amount,
		Currency:             req.Currency,
		Reference:            req.Reference,
		Status:               ledger.StatusPending,
		CorrelationID:        correlationID,
	}
	envBytes, err := h.orch.EmitInitiated(r.Context(), t)
	if err != nil {
		problem(w, http.StatusInternalServerError, "Cannot build event", err.Error())
		return
	}
	outbox := &ledger.OutboxEvent{
		AggregateID: t.ID, Topic: "transaction-events",
		EventType: "TransferInitiated", Envelope: envBytes,
	}
	stored, existed, err := h.repo.CreateTransferWithIdem(r.Context(), t, outbox)
	if err != nil {
		problem(w, http.StatusInternalServerError, "DB error", err.Error())
		return
	}
	if !existed {
		// Fire-and-forget the saga in a goroutine so we can return 202 quickly.
		go h.orch.Execute(detachedCtx(r.Context()), stored.ID)
	}
	writeJSON(w, http.StatusAccepted, toDTO(stored))
}

func (h *Handler) GetTransfer(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "transferId"))
	if err != nil {
		problem(w, http.StatusBadRequest, "Invalid id", "")
		return
	}
	t, err := h.repo.GetTransfer(r.Context(), id)
	if err != nil {
		if errors.Is(err, ledger.ErrNotFound) {
			problem(w, http.StatusNotFound, "Not found", "")
			return
		}
		problem(w, http.StatusInternalServerError, "DB error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, toDTO(t))
}

func (h *Handler) GetSaga(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "transferId"))
	if err != nil {
		problem(w, http.StatusBadRequest, "Invalid id", "")
		return
	}
	steps, err := h.repo.ListSteps(r.Context(), id)
	if err != nil {
		problem(w, http.StatusInternalServerError, "DB error", err.Error())
		return
	}
	out := make([]SagaStepDTO, 0, len(steps))
	for _, s := range steps {
		out = append(out, SagaStepDTO{
			Step: s.Step, Status: s.Status, OccurredAt: s.OccurredAt, Details: s.Details,
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (h *Handler) ListTransfers(w http.ResponseWriter, r *http.Request) {
	f := ledger.ListFilter{}
	if v := r.URL.Query().Get("accountId"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			problem(w, http.StatusBadRequest, "Invalid accountId", "")
			return
		}
		f.AccountID = &id
	}
	f.Status = r.URL.Query().Get("status")
	if v := r.URL.Query().Get("from"); v != "" {
		ts, err := time.Parse(time.RFC3339, v)
		if err != nil {
			problem(w, http.StatusBadRequest, "Invalid from", "")
			return
		}
		f.From = &ts
	}
	if v := r.URL.Query().Get("to"); v != "" {
		ts, err := time.Parse(time.RFC3339, v)
		if err != nil {
			problem(w, http.StatusBadRequest, "Invalid to", "")
			return
		}
		f.To = &ts
	}
	if v := r.URL.Query().Get("limit"); v != "" {
		n, _ := strconv.Atoi(v)
		f.Limit = n
	}
	rows, err := h.repo.ListTransfers(r.Context(), f)
	if err != nil {
		problem(w, http.StatusInternalServerError, "DB error", err.Error())
		return
	}
	out := make([]TransferDTO, 0, len(rows))
	for _, t := range rows {
		out = append(out, toDTO(t))
	}
	writeJSON(w, http.StatusOK, out)
}

// helper to ensure auth claims are present; callers may use this in protected
// routes to read the current user.
func requireAuth(r *http.Request) (auth.Claims, error) {
	c, ok := auth.FromContext(r.Context())
	if !ok {
		return c, fmt.Errorf("no claims")
	}
	return c, nil
}
