package accounts

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/cenkalti/backoff/v4"
	"github.com/google/uuid"
	"github.com/sony/gobreaker"
)

// Client talks to the accounts-service internal API.
type Client interface {
	Reserve(ctx context.Context, accountID uuid.UUID, req ReserveRequest) (*ReserveResponse, error)
	Commit(ctx context.Context, accountID, reservationID uuid.UUID, idem string) error
	Release(ctx context.Context, accountID, reservationID uuid.UUID, idem string) error
	Deposit(ctx context.Context, accountID uuid.UUID, req DepositRequest) error
}

type ReserveRequest struct {
	TransactionID  uuid.UUID `json:"transactionId"`
	Amount         int64     `json:"amount"`
	Currency       string    `json:"currency"`
	IdempotencyKey string    `json:"idempotencyKey"`
}

type ReserveResponse struct {
	ReservationID uuid.UUID `json:"reservationId"`
}

type DepositRequest struct {
	TransactionID  uuid.UUID `json:"transactionId"`
	Amount         int64     `json:"amount"`
	Currency       string    `json:"currency"`
	Description    string    `json:"description,omitempty"`
	IdempotencyKey string    `json:"idempotencyKey"`
}

type httpClient struct {
	base          string
	token         string
	hc            *http.Client
	breaker       *gobreaker.CircuitBreaker
}

// NewHTTPClient builds an accounts-service HTTP client wrapped with a circuit
// breaker. Callers should also wrap calls with backoff.Retry for transient
// failures (the saga does this).
func NewHTTPClient(base, token string, timeout time.Duration) Client {
	cb := gobreaker.NewCircuitBreaker(gobreaker.Settings{
		Name:        "accounts-service",
		MaxRequests: 3,
		Interval:    30 * time.Second,
		Timeout:     15 * time.Second,
		ReadyToTrip: func(c gobreaker.Counts) bool { return c.ConsecutiveFailures > 5 },
	})
	return &httpClient{
		base:    base,
		token:   token,
		hc:      &http.Client{Timeout: timeout},
		breaker: cb,
	}
}

func (c *httpClient) do(ctx context.Context, method, path string, body any, out any) error {
	op := func() error {
		var rdr io.Reader
		if body != nil {
			b, _ := json.Marshal(body)
			rdr = bytes.NewReader(b)
		}
		req, err := http.NewRequestWithContext(ctx, method, c.base+path, rdr)
		if err != nil {
			return backoff.Permanent(err)
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Internal-Token", c.token)
		_, err = c.breaker.Execute(func() (any, error) {
			resp, err := c.hc.Do(req)
			if err != nil {
				return nil, err
			}
			defer resp.Body.Close()
			if resp.StatusCode >= 500 {
				return nil, fmt.Errorf("accounts-service status %d", resp.StatusCode)
			}
			if resp.StatusCode >= 400 {
				b, _ := io.ReadAll(resp.Body)
				return nil, backoff.Permanent(&APIError{Status: resp.StatusCode, Body: string(b)})
			}
			if out != nil {
				return nil, json.NewDecoder(resp.Body).Decode(out)
			}
			return nil, nil
		})
		return err
	}
	bo := backoff.WithContext(backoff.NewExponentialBackOff(), ctx)
	return backoff.Retry(op, backoff.WithMaxRetries(bo, 4))
}

type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string { return fmt.Sprintf("accounts api %d: %s", e.Status, e.Body) }

// IsPermanent reports whether the error should not be retried / compensated.
func IsPermanent(err error) bool {
	var pe *APIError
	return errors.As(err, &pe)
}

func (c *httpClient) Reserve(ctx context.Context, accountID uuid.UUID, req ReserveRequest) (*ReserveResponse, error) {
	var out ReserveResponse
	if err := c.do(ctx, http.MethodPost,
		fmt.Sprintf("/internal/api/v1/accounts/%s/reservations", accountID), req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *httpClient) Commit(ctx context.Context, accountID, reservationID uuid.UUID, idem string) error {
	return c.do(ctx, http.MethodPost,
		fmt.Sprintf("/internal/api/v1/accounts/%s/reservations/%s/commit", accountID, reservationID),
		map[string]string{"idempotencyKey": idem}, nil)
}

func (c *httpClient) Release(ctx context.Context, accountID, reservationID uuid.UUID, idem string) error {
	return c.do(ctx, http.MethodPost,
		fmt.Sprintf("/internal/api/v1/accounts/%s/reservations/%s/release", accountID, reservationID),
		map[string]string{"idempotencyKey": idem}, nil)
}

func (c *httpClient) Deposit(ctx context.Context, accountID uuid.UUID, req DepositRequest) error {
	return c.do(ctx, http.MethodPost,
		fmt.Sprintf("/internal/api/v1/accounts/%s/deposits", accountID), req, nil)
}
