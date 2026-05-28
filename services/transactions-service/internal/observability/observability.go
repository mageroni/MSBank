package observability

import (
	"context"
	"log/slog"
	"net/http"
	"os"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

func NewLogger(level string) *slog.Logger {
	var lvl slog.Level
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	h := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: lvl})
	return slog.New(h)
}

type Metrics struct {
	HTTPRequests *prometheus.CounterVec
	HTTPDuration *prometheus.HistogramVec
	SagaSteps    *prometheus.CounterVec
	Transfers    *prometheus.CounterVec
}

func NewMetrics(reg *prometheus.Registry) *Metrics {
	factory := promauto.With(reg)
	return &Metrics{
		HTTPRequests: factory.NewCounterVec(prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "HTTP requests",
		}, []string{"method", "path", "status"}),
		HTTPDuration: factory.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP request duration",
			Buckets: prometheus.DefBuckets,
		}, []string{"method", "path"}),
		SagaSteps: factory.NewCounterVec(prometheus.CounterOpts{
			Name: "saga_steps_total",
			Help: "Saga steps executed",
		}, []string{"step", "status"}),
		Transfers: factory.NewCounterVec(prometheus.CounterOpts{
			Name: "transfers_total",
			Help: "Transfers by terminal status",
		}, []string{"status"}),
	}
}

func MetricsHandler(reg *prometheus.Registry) http.Handler {
	return promhttp.HandlerFor(reg, promhttp.HandlerOpts{Registry: reg})
}

// Shutdown is a no-op placeholder kept for future tracer provider hooks.
func Shutdown(_ context.Context) error { return nil }
