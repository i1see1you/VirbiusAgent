package main

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
)

type PidMap struct {
	client *redis.Client
}

func NewPidMap(redisURL string) (*PidMap, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("parse pidmap redis url: %w", err)
	}
	return &PidMap{client: redis.NewClient(opts)}, nil
}

func (pm *PidMap) Lookup(ctx context.Context, traceID string) *PidInfo {
	key := fmt.Sprintf("pid_trace:%s", traceID)
	val, err := pm.client.Get(ctx, key).Result()
	if err != nil {
		return nil
	}

	var entry map[string]any
	if err := jsonUnmarshal([]byte(val), &entry); err != nil {
		return nil
	}

	return &PidInfo{
		HostPID:     intVal(entry, "host_pid"),
		NsPid:       intVal(entry, "ns_pid"),
		CgroupID:    strVal(entry, "cgroup_id"),
		ContainerID: strVal(entry, "container_id"),
	}
}

func (pm *PidMap) LookupByPID(ctx context.Context, hostPID int) *PidInfo {
	key := fmt.Sprintf("pid_trace:%d", hostPID)
	val, err := pm.client.Get(ctx, key).Result()
	if err != nil {
		return nil
	}
	var entry map[string]any
	if err := jsonUnmarshal([]byte(val), &entry); err != nil {
		return nil
	}
	return &PidInfo{
		HostPID:     hostPID,
		CgroupID:    strVal(entry, "cgroup_id"),
		ContainerID: strVal(entry, "container_id"),
	}
}

func jsonUnmarshal(data []byte, v any) error {
	return jsonUnmarshalImpl(data, v)
}

func init() {
	_ = strconv.Itoa
	_ = time.Second
}
