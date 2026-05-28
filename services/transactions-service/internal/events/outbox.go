package events

import (
	"context"
	"log/slog"
	"time"

	"github.com/msbank/transactions-service/internal/ledger"
)

// OutboxPublisher periodically reads unpublished rows from the outbox and
// pushes them to Kafka. It is the only path from saga state to the wire so
// publication is at-least-once and consistent with the DB.
type OutboxPublisher struct {
	repo      *ledger.Repo
	pub       Publisher
	logger    *slog.Logger
	interval  time.Duration
	batchSize int
}

func NewOutboxPublisher(repo *ledger.Repo, pub Publisher, logger *slog.Logger, interval time.Duration) *OutboxPublisher {
	return &OutboxPublisher{repo: repo, pub: pub, logger: logger, interval: interval, batchSize: 50}
}

func (o *OutboxPublisher) Run(ctx context.Context) {
	t := time.NewTicker(o.interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			o.tick(ctx)
		}
	}
}

func (o *OutboxPublisher) tick(ctx context.Context) {
	rows, err := o.repo.PendingOutbox(ctx, o.batchSize)
	if err != nil {
		o.logger.Warn("outbox poll failed", "err", err)
		return
	}
	for _, e := range rows {
		if err := o.pub.Publish(ctx, e.Topic, e.AggregateID.String(), e.Envelope); err != nil {
			o.logger.Warn("outbox publish failed", "id", e.ID, "err", err)
			continue
		}
		if err := o.repo.MarkOutboxPublished(ctx, e.ID); err != nil {
			o.logger.Warn("outbox mark failed", "id", e.ID, "err", err)
		}
	}
}
