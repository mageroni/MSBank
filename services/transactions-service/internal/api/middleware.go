package api

import (
	"context"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
)

type ctxKey int

const ctxKeyCorrelation ctxKey = iota

// CorrelationID middleware reads X-Correlation-Id (or generates one) and
// attaches it to the request context and response header.
func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cid := r.Header.Get("X-Correlation-Id")
		if cid == "" {
			cid = uuid.NewString()
		}
		w.Header().Set("X-Correlation-Id", cid)
		ctx := context.WithValue(r.Context(), ctxKeyCorrelation, cid)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func CorrelationFromCtx(ctx context.Context) string {
	v, _ := ctx.Value(ctxKeyCorrelation).(string)
	return v
}

// statusRecorder captures the response status code for metrics & logging.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// Observe logs each request and emits Prometheus metrics.
func Observe(logger *slog.Logger, counter *prometheus.CounterVec, hist *prometheus.HistogramVec) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(rec, r)
			dur := time.Since(start).Seconds()
			path := r.URL.Path
			counter.WithLabelValues(r.Method, path, strconv.Itoa(rec.status)).Inc()
			hist.WithLabelValues(r.Method, path).Observe(dur)
			logger.Info("http",
				"method", r.Method,
				"path", path,
				"status", rec.status,
				"duration_ms", time.Since(start).Milliseconds(),
				"correlation_id", CorrelationFromCtx(r.Context()),
			)
		})
	}
}
