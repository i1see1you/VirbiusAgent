package main

import (
	"encoding/json"
	"fmt"
	"strconv"
)

type AuditEvent struct {
	EventID         string          `json:"event_id"`
	TraceID         string          `json:"trace_id"`
	TenantID        string          `json:"tenant_id"`
	Layer           string          `json:"layer"`
	RuleID          string          `json:"rule_id"`
	RuleRevision    int             `json:"rule_revision"`
	ReasonCode      string          `json:"reason_code"`
	EffectiveAction string          `json:"effective_action"`
	MaxRiskScore    int             `json:"max_risk_score"`
	ToolName        string          `json:"tool_name"`
	SessionID       string          `json:"session_id"`
	AppID           string          `json:"app_id"`
	UserID          string          `json:"user_id"`
	DeviceID        string          `json:"device_id"`
	SessionRisk     int             `json:"session_risk_score"`
	ToolArgsSnippet string          `json:"tool_args_snippet"`
	InterceptedAt   string          `json:"intercepted_at"`
	Pid             *PidInfo        `json:"_pid,omitempty"`
}

type PidInfo struct {
	HostPID     int    `json:"host_pid"`
	NsPid       int    `json:"ns_pid"`
	CgroupID    string `json:"cgroup_id"`
	ContainerID string `json:"container_id"`
}

func parseAuditEvent(raw []byte) (*AuditEvent, error) {
	var rawMap map[string]any
	if err := json.Unmarshal(raw, &rawMap); err != nil {
		return nil, fmt.Errorf("json unmarshal: %w", err)
	}

	ev := &AuditEvent{}
	ev.EventID = strVal(rawMap, "event_id")
	ev.TraceID = strVal(rawMap, "trace_id")
	ev.TenantID = strVal(rawMap, "tenant_id")
	ev.Layer = strVal(rawMap, "layer")
	ev.RuleID = strVal(rawMap, "rule_id")
	ev.RuleRevision = intVal(rawMap, "rule_revision")
	ev.ReasonCode = strVal(rawMap, "reason_code")
	ev.EffectiveAction = strVal(rawMap, "effective_action")
	if ev.EffectiveAction == "" {
		ev.EffectiveAction = strVal(rawMap, "action")
	}
	ev.MaxRiskScore = intVal(rawMap, "max_risk_score")
	ev.ToolName = strVal(rawMap, "tool_name")
	ev.SessionID = strVal(rawMap, "session_id")
	ev.AppID = strVal(rawMap, "app_id")
	ev.UserID = strVal(rawMap, "user_id")
	ev.DeviceID = strVal(rawMap, "device_id")
	ev.SessionRisk = intVal(rawMap, "session_risk_score")
	ev.ToolArgsSnippet = strVal(rawMap, "tool_args_snippet")
	ev.InterceptedAt = strVal(rawMap, "intercepted_at")
	if ev.InterceptedAt == "" {
		ev.InterceptedAt = strVal(rawMap, "timestamp")
	}
	return ev, nil
}

func strVal(m map[string]any, key string) string {
	if v, ok := m[key]; ok {
		if s, ok := v.(string); ok {
			return s
		}
		return fmt.Sprintf("%v", v)
	}
	return ""
}

func intVal(m map[string]any, key string) int {
	if v, ok := m[key]; ok {
		switch n := v.(type) {
		case float64:
			return int(n)
		case int:
			return n
		case string:
			if i, err := strconv.Atoi(n); err == nil {
				return i
			}
		}
	}
	return 0
}
