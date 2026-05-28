package api

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/msbank/transactions-service/internal/auth"
	"github.com/msbank/transactions-service/internal/observability"
)

// Router wires HTTP routes and middleware.
func Router(h *Handler, ver *auth.Verifier, logger *slog.Logger, m *observability.Metrics,
	reg *prometheus.Registry, db *pgxpool.Pool, ready func(context.Context) bool) http.Handler {
	r := chi.NewRouter()
	r.Use(CorrelationID)
	r.Use(Observe(logger, m.HTTPRequests, m.HTTPDuration))

	r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	r.Get("/readyz", func(w http.ResponseWriter, r *http.Request) {
		if !ready(r.Context()) {
			problem(w, http.StatusServiceUnavailable, "not ready", "")
			return
		}
		if err := db.Ping(r.Context()); err != nil {
			problem(w, http.StatusServiceUnavailable, "db down", err.Error())
			return
		}
		_, _ = w.Write([]byte("ready"))
	})
	r.Handle("/metrics", observability.MetricsHandler(reg))

	r.Route("/api/v1", func(r chi.Router) {
		r.Use(ver.Middleware)
		r.Post("/transfers", h.CreateTransfer)
		r.Get("/transfers", h.ListTransfers)
		r.Get("/transfers/{transferId}", h.GetTransfer)
		r.Get("/transfers/{transferId}/saga", h.GetSaga)
	})

	return r
}
