package saga_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"log/slog"
	"os"

	"github.com/msbank/transactions-service/internal/accounts"
	"github.com/msbank/transactions-service/internal/idem"
	"github.com/msbank/transactions-service/internal/ledger"
	"github.com/msbank/transactions-service/internal/saga"
)

// TestSagaIntegration spins up Postgres in a container, applies the
// migrations, stubs accounts-service with httptest, and verifies that a
// transfer end-to-end (a) reaches COMPLETED and (b) produces the expected
// outbox events. Skipped under `go test -short`.
func TestSagaIntegration(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test in short mode")
	}
	ctx := context.Background()

	pg, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: testcontainers.ContainerRequest{
			Image:        "postgres:16-alpine",
			ExposedPorts: []string{"5432/tcp"},
			Env: map[string]string{
				"POSTGRES_USER":     "test",
				"POSTGRES_PASSWORD": "test",
				"POSTGRES_DB":       "test",
			},
			WaitingFor: wait.ForListeningPort("5432/tcp").WithStartupTimeout(60 * time.Second),
		},
		Started: true,
	})
	require.NoError(t, err)
	t.Cleanup(func() { _ = pg.Terminate(ctx) })

	host, _ := pg.Host(ctx)
	port, _ := pg.MappedPort(ctx, "5432")
	dsn := "postgres://test:test@" + host + ":" + port.Port() + "/test?sslmode=disable"

	pool, err := pgxpool.New(ctx, dsn)
	require.NoError(t, err)
	t.Cleanup(pool.Close)
	require.NoError(t, applyMigrations(ctx, pool))

	stub := newAccountsStub()
	defer stub.Close()
	client := accounts.NewHTTPClient(stub.URL, "test-token", 3*time.Second)

	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError}))
	stepMetric := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "it_saga_steps"}, []string{"step", "status"})
	terminal := prometheus.NewCounterVec(prometheus.CounterOpts{Name: "it_transfers"}, []string{"status"})
	repo := ledger.NewRepo(pool)
	orch := saga.NewOrchestrator(repo, client, idem.NewStore(nil), logger, "transaction-events", stepMetric, terminal)

	tr := &ledger.Transfer{
		ID: uuid.New(), IdempotencyKey: uuid.New(),
		SourceAccountID: uuid.New(), DestinationAccountID: uuid.New(),
		Amount: 1234, Currency: "USD", Status: ledger.StatusPending,
	}
	_, _, err = repo.CreateTransferWithIdem(ctx, tr, nil)
	require.NoError(t, err)

	orch.Execute(ctx, tr.ID)

	got, err := repo.GetTransfer(ctx, tr.ID)
	require.NoError(t, err)
	require.Equal(t, ledger.StatusCompleted, got.Status)

	outbox, err := repo.PendingOutbox(ctx, 10)
	require.NoError(t, err)
	require.NotEmpty(t, outbox)
	require.Equal(t, "TransferCompleted", outbox[0].EventType)
}

func applyMigrations(ctx context.Context, pool *pgxpool.Pool) error {
	migrationSQL, err := os.ReadFile("../../migrations/0001_init.up.sql")
	if err != nil {
		return err
	}
	_, err = pool.Exec(ctx, string(migrationSQL))
	return err
}

func newAccountsStub() *httptest.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/internal/api/v1/accounts/", func(w http.ResponseWriter, r *http.Request) {
		// Treat all reservation/deposit calls as success.
		if r.Method == http.MethodPost {
			if endsWith(r.URL.Path, "/reservations") {
				_ = json.NewEncoder(w).Encode(map[string]string{"reservationId": uuid.NewString()})
				return
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}
		http.NotFound(w, r)
	})
	return httptest.NewServer(mux)
}

func endsWith(s, suffix string) bool { return len(s) >= len(suffix) && s[len(s)-len(suffix):] == suffix }
