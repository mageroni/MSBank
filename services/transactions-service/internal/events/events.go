package events

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/twmb/franz-go/pkg/kgo"
)

// Envelope is the CloudEvents-inspired wrapper used on all topics.
type Envelope struct {
	EventID       uuid.UUID       `json:"eventId"`
	EventType     string          `json:"eventType"`
	EventVersion  int             `json:"eventVersion"`
	OccurredAt    time.Time       `json:"occurredAt"`
	CorrelationID string          `json:"correlationId,omitempty"`
	CausationID   string          `json:"causationId,omitempty"`
	Source        string          `json:"source"`
	Data          json.RawMessage `json:"data"`
}

func NewEnvelope(eventType, source, correlationID string, data any) (Envelope, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return Envelope{}, err
	}
	return Envelope{
		EventID:       uuid.New(),
		EventType:     eventType,
		EventVersion:  1,
		OccurredAt:    time.Now().UTC(),
		CorrelationID: correlationID,
		Source:        source,
		Data:          raw,
	}, nil
}

// Publisher publishes a single record. Implemented by Kafka and by tests.
type Publisher interface {
	Publish(ctx context.Context, topic, key string, value []byte) error
	Close()
}

type KafkaPublisher struct {
	cl *kgo.Client
}

func NewKafkaPublisher(brokers []string) (*KafkaPublisher, error) {
	cl, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.AllowAutoTopicCreation(),
		kgo.ProducerLinger(50*time.Millisecond),
	)
	if err != nil {
		return nil, err
	}
	return &KafkaPublisher{cl: cl}, nil
}

func (p *KafkaPublisher) Publish(ctx context.Context, topic, key string, value []byte) error {
	rec := &kgo.Record{Topic: topic, Key: []byte(key), Value: value}
	return p.cl.ProduceSync(ctx, rec).FirstErr()
}

func (p *KafkaPublisher) Close() {
	if p.cl != nil {
		p.cl.Close()
	}
}

// Ping returns nil if the broker is reachable.
func (p *KafkaPublisher) Ping(ctx context.Context) error {
	return p.cl.Ping(ctx)
}

// Consumer is a minimal consumer for account-events that just logs receipt.
// In a real system this would update saga progress when an async ack arrives
// from accounts-service.
type Consumer struct {
	cl     *kgo.Client
	logger *slog.Logger
}

func NewConsumer(brokers []string, topic, groupID string, logger *slog.Logger) (*Consumer, error) {
	cl, err := kgo.NewClient(
		kgo.SeedBrokers(brokers...),
		kgo.ConsumerGroup(groupID),
		kgo.ConsumeTopics(topic),
	)
	if err != nil {
		return nil, err
	}
	return &Consumer{cl: cl, logger: logger}, nil
}

func (c *Consumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		fetches := c.cl.PollFetches(ctx)
		if fetches.IsClientClosed() {
			return
		}
		fetches.EachError(func(t string, p int32, err error) {
			c.logger.Warn("consume error", "topic", t, "partition", p, "err", err)
		})
		fetches.EachRecord(func(r *kgo.Record) {
			var env Envelope
			if err := json.Unmarshal(r.Value, &env); err == nil {
				c.logger.Debug("account event received",
					"eventType", env.EventType,
					"correlationId", env.CorrelationID,
				)
			}
		})
	}
}

func (c *Consumer) Close() { c.cl.Close() }
