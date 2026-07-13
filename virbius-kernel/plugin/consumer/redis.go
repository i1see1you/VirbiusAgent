package main

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisConsumer struct {
	client       *redis.Client
	streams      []StreamConfig
	pidMap       *PidMap
	eventCh      chan<- *AuditEvent
}

type StreamConfig struct {
	Stream        string
	ConsumerGroup string
}

func NewRedisConsumer(redisURL string, streams []StreamConfig, pidMapRedisURL string, eventCh chan<- *AuditEvent) (*RedisConsumer, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	client := redis.NewClient(opts)

	pidMap, err := NewPidMap(pidMapRedisURL)
	if err != nil {
		return nil, fmt.Errorf("init pidmap: %w", err)
	}

	return &RedisConsumer{
		client:  client,
		streams: streams,
		pidMap:  pidMap,
		eventCh: eventCh,
	}, nil
}

func (rc *RedisConsumer) Run(ctx context.Context) {
	for _, sc := range rc.streams {
		rc.ensureGroup(ctx, sc.Stream, sc.ConsumerGroup)
		go rc.consumeStream(ctx, sc)
	}
}

func (rc *RedisConsumer) ensureGroup(ctx context.Context, stream, group string) {
	for attempt := 0; attempt < 10; attempt++ {
		err := rc.client.XGroupCreateMkStream(ctx, stream, group, "$").Err()
		if err == nil {
			return
		}
		if err.Error() != "BUSYGROUP Consumer Group name already exists" {
			time.Sleep(3 * time.Second)
			continue
		}
		return
	}
}

func (rc *RedisConsumer) consumeStream(ctx context.Context, sc StreamConfig) {
	consumerName := fmt.Sprintf("falco-%d", time.Now().UnixNano()%100000)
	backoff := time.Second

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		results, err := rc.client.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group:    sc.ConsumerGroup,
			Consumer: consumerName,
			Streams:  []string{sc.Stream, ">"},
			Count:    32,
			Block:    5 * time.Second,
		}).Result()

		if err != nil && err != redis.Nil {
			time.Sleep(backoff)
			if backoff < 60*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = time.Second

		for _, stream := range results {
			for _, msg := range stream.Messages {
				rc.processMessage(ctx, sc, msg)
				rc.client.XAck(ctx, sc.Stream, sc.ConsumerGroup, msg.ID)
			}
		}
	}
}

func (rc *RedisConsumer) processMessage(ctx context.Context, sc StreamConfig, msg redis.XMessage) {
	raw := extractPayload(msg)
	if raw == nil {
		return
	}

	ev, err := parseAuditEvent(raw)
	if err != nil {
		return
	}

	if ev.TraceID != "" {
		if pidInfo := rc.pidMap.Lookup(ctx, ev.TraceID); pidInfo != nil {
			ev.Pid = pidInfo
		}
	}

	select {
	case rc.eventCh <- ev:
	case <-ctx.Done():
	}
}

func extractPayload(msg redis.XMessage) []byte {
	for _, key := range []string{"payload", "data"} {
		if v, ok := msg.Values[key]; ok {
			if s, ok := v.(string); ok {
				return []byte(s)
			}
		}
	}
	if len(msg.Values) > 0 {
		if data, err := jsonMarshal(msg.Values); err == nil {
			return data
		}
	}
	return nil
}

func jsonMarshal(v any) ([]byte, error) {
	return jsonMarshalImpl(v)
}
