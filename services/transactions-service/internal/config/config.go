package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

type Config struct {
	HTTPPort                 int
	LogLevel                 string
	DatabaseURL              string
	KafkaBootstrapServers    string
	RedisURL                 string
	AccountsServiceURL       string
	InternalToken            string
	JWTIssuer                string
	JWTAudience              string
	JWTJwksURI               string
	OTELExporterEndpoint     string
	ServiceName              string
	TransactionEventsTopic   string
	AccountEventsTopic       string
	SagaRecoveryInterval     time.Duration
	OutboxPublishInterval    time.Duration
	HTTPClientTimeout        time.Duration
	GracefulShutdownTimeout  time.Duration
}

func Load() (*Config, error) {
	c := &Config{
		HTTPPort:                getEnvInt("HTTP_PORT", 8083),
		LogLevel:                getEnv("LOG_LEVEL", "info"),
		DatabaseURL:             getEnv("TRANSACTIONS_DB_URL", ""),
		KafkaBootstrapServers:   getEnv("KAFKA_BOOTSTRAP_SERVERS", ""),
		RedisURL:                getEnv("REDIS_URL", ""),
		AccountsServiceURL:      getEnv("ACCOUNTS_SERVICE_URL", ""),
		InternalToken:           getEnv("INTERNAL_TOKEN", "dev-internal-token"),
		JWTIssuer:               getEnv("JWT_ISSUER", ""),
		JWTAudience:             getEnv("JWT_AUDIENCE", ""),
		JWTJwksURI:              getEnv("JWT_JWKS_URI", ""),
		OTELExporterEndpoint:    getEnv("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
		ServiceName:             getEnv("SERVICE_NAME", "transactions-service"),
		TransactionEventsTopic:  getEnv("TRANSACTION_EVENTS_TOPIC", "transaction-events"),
		AccountEventsTopic:      getEnv("ACCOUNT_EVENTS_TOPIC", "account-events"),
		SagaRecoveryInterval:    getEnvDuration("SAGA_RECOVERY_INTERVAL", 30*time.Second),
		OutboxPublishInterval:   getEnvDuration("OUTBOX_PUBLISH_INTERVAL", 2*time.Second),
		HTTPClientTimeout:       getEnvDuration("HTTP_CLIENT_TIMEOUT", 5*time.Second),
		GracefulShutdownTimeout: getEnvDuration("SHUTDOWN_TIMEOUT", 20*time.Second),
	}
	if c.DatabaseURL == "" {
		return nil, fmt.Errorf("TRANSACTIONS_DB_URL is required")
	}
	return c, nil
}

func getEnv(k, def string) string {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		return v
	}
	return def
}

func getEnvInt(k string, def int) int {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func getEnvDuration(k string, def time.Duration) time.Duration {
	if v, ok := os.LookupEnv(k); ok && v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}
