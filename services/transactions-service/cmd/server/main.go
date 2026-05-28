package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/postgres"
	_ "github.com/golang-migrate/migrate/v4/source/file"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/redis/go-redis/v9"

	"github.com/msbank/transactions-service/internal/accounts"
	"github.com/msbank/transactions-service/internal/api"
	"github.com/msbank/transactions-service/internal/auth"
	"github.com/msbank/transactions-service/internal/config"
	"github.com/msbank/transactions-service/internal/events"
	"github.com/msbank/transactions-service/internal/idem"
	"github.com/msbank/transactions-service/internal/ledger"
	"github.com/msbank/transactions-service/internal/observability"
	"github.com/msbank/transactions-service/internal/saga"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "config error:", err)
		os.Exit(1)
	}
	logger := observability.NewLogger(cfg.LogLevel)
	slog.SetDefault(logger)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := run(ctx, cfg, logger); err != nil {
		logger.Error("service exited", "err", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, cfg *config.Config, logger *slog.Logger) error {
	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return fmt.Errorf("pg connect: %w", err)
	}
	defer pool.Close()

	if err := runMigrations(cfg.DatabaseURL, logger); err != nil {
		return fmt.Errorf("migrations: %w", err)
	}

	repo := ledger.NewRepo(pool)

	// Redis is optional in dev: if not configured, idem store no-ops.
	var rdb *redis.Client
	if cfg.RedisURL != "" {
		opt, err := redis.ParseURL(cfg.RedisURL)
		if err != nil {
			return fmt.Errorf("redis url: %w", err)
		}
		rdb = redis.NewClient(opt)
		defer rdb.Close()
	}
	idemStore := idem.NewStore(rdb)

	// Kafka publisher (optional in dev).
	var pub events.Publisher
	var kafkaReady atomic.Bool
	if cfg.KafkaBootstrapServers != "" {
		kp, err := events.NewKafkaPublisher(strings.Split(cfg.KafkaBootstrapServers, ","))
		if err != nil {
			logger.Warn("kafka init failed; continuing without publisher", "err", err)
		} else {
			pub = kp
			kafkaReady.Store(true)
			defer kp.Close()
		}
	}

	accountsClient := accounts.NewHTTPClient(cfg.AccountsServiceURL, cfg.InternalToken, cfg.HTTPClientTimeout)

	reg := prometheus.NewRegistry()
	metrics := observability.NewMetrics(reg)

	orch := saga.NewOrchestrator(repo, accountsClient, idemStore, logger, cfg.TransactionEventsTopic, metrics.SagaSteps, metrics.Transfers)

	if pub != nil {
		ob := events.NewOutboxPublisher(repo, pub, logger, cfg.OutboxPublishInterval)
		go ob.Run(ctx)
	}

	recovery := saga.NewRecovery(repo, orch, logger, cfg.SagaRecoveryInterval)
	go recovery.Run(ctx)

	verifier := auth.NewVerifier(cfg.JWTIssuer, cfg.JWTAudience, cfg.JWTJwksURI)
	handler := api.NewHandler(repo, orch)

	ready := func(_ context.Context) bool {
		// In dev mode without Kafka, still report ready so demos work.
		if cfg.KafkaBootstrapServers == "" {
			return true
		}
		return kafkaReady.Load()
	}
	router := api.Router(handler, verifier, logger, metrics, reg, pool, ready)

	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.HTTPPort),
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		logger.Info("http listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http serve", "err", err)
		}
	}()

	<-ctx.Done()
	logger.Info("shutdown signal received; draining")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.GracefulShutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Warn("http shutdown error", "err", err)
	}
	return nil
}

func runMigrations(dbURL string, logger *slog.Logger) error {
	u, err := url.Parse(dbURL)
	if err != nil {
		return err
	}
	// golang-migrate uses its own URL scheme for postgres
	if u.Scheme == "postgres" || u.Scheme == "postgresql" {
		u.Scheme = "postgres"
	}
	m, err := migrate.New("file://./migrations", u.String())
	if err != nil {
		return err
	}
	defer m.Close()
	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return err
	}
	logger.Info("migrations applied")
	return nil
}
