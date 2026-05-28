package idem

import (
	"context"
	"errors"
	"time"

	"github.com/redis/go-redis/v9"
)

// Store provides distributed advisory locks for saga step execution. The
// authoritative idempotency record for transfer creation lives in Postgres;
// Redis is used here as a fast-path lock for in-flight saga work.
type Store struct {
	rdb *redis.Client
	ttl time.Duration
}

func NewStore(rdb *redis.Client) *Store {
	return &Store{rdb: rdb, ttl: 30 * time.Second}
}

// Lock attempts to acquire a lock with the given key. Returns true if acquired.
func (s *Store) Lock(ctx context.Context, key string) (bool, error) {
	if s == nil || s.rdb == nil {
		return true, nil
	}
	ok, err := s.rdb.SetNX(ctx, "saga:lock:"+key, "1", s.ttl).Result()
	if err != nil && !errors.Is(err, redis.Nil) {
		return false, err
	}
	return ok, nil
}

func (s *Store) Unlock(ctx context.Context, key string) error {
	if s == nil || s.rdb == nil {
		return nil
	}
	return s.rdb.Del(ctx, "saga:lock:"+key).Err()
}
