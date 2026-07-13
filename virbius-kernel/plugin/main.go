package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type PluginConfig struct {
	RedisURL       string        `json:"redis_url"`
	RedisStreams   []StreamConfig `json:"redis_streams"`
	PidMapRedisURL string        `json:"pid_map_redis_url"`
	EventTTL       time.Duration `json:"-"`
}

func loadConfig() (*PluginConfig, error) {
	cfg := &PluginConfig{}

	configFile := os.Getenv("VIRBIUS_AUDIT_CONFIG")
	if configFile != "" {
		data, err := os.ReadFile(configFile)
		if err != nil {
			return nil, fmt.Errorf("read config file: %w", err)
		}
		if err := json.Unmarshal(data, cfg); err != nil {
			return nil, fmt.Errorf("parse config: %w", err)
		}
	}

	if v := os.Getenv("VIRBIUS_AUDIT_REDIS_URL"); v != "" {
		cfg.RedisURL = v
	}
	if v := os.Getenv("VIRBIUS_AUDIT_PID_MAP_REDIS_URL"); v != "" {
		cfg.PidMapRedisURL = v
	}
	if v := os.Getenv("VIRBIUS_AUDIT_STREAMS"); v != "" {
		for _, pair := range strings.Split(v, ",") {
			parts := strings.SplitN(pair, ":", 2)
			if len(parts) == 2 {
				cfg.RedisStreams = append(cfg.RedisStreams, StreamConfig{
					Stream:        parts[0],
					ConsumerGroup: parts[1],
				})
			}
		}
	}

	if cfg.RedisURL == "" {
		return nil, fmt.Errorf("redis_url is required")
	}
	if len(cfg.RedisStreams) == 0 {
		cfg.RedisStreams = []StreamConfig{
			{Stream: "virbius:audit:stream", ConsumerGroup: "falco-virbius"},
			{Stream: "virbius:audit:events", ConsumerGroup: "falco-virbius"},
		}
	}
	if cfg.PidMapRedisURL == "" {
		cfg.PidMapRedisURL = cfg.RedisURL
	}
	cfg.EventTTL = 3600 * time.Second

	return cfg, nil
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		fmt.Fprintf(os.Stderr, "config error: %v\n", err)
		os.Exit(1)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	eventCh := make(chan *AuditEvent, 512)

	consumer, err := NewRedisConsumer(cfg.RedisURL, cfg.RedisStreams, cfg.PidMapRedisURL, eventCh)
	if err != nil {
		fmt.Fprintf(os.Stderr, "consumer init error: %v\n", err)
		os.Exit(1)
	}

	consumer.Run(ctx)

	for {
		select {
		case ev := <-eventCh:
			output, _ := json.Marshal(ev)
			fmt.Printf(`{"plugin":"virbius-audit","event":%s}`+"\n", output)
		case sig := <-sigCh:
			fmt.Fprintf(os.Stderr, "received signal %v, shutting down\n", sig)
			cancel()
			return
		}
	}
}

func init() {
	_ = strconv.Itoa
}
