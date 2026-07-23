package expr

import (
	"encoding/json"
	"testing"
)

func TestCompileAndEval_Simple(t *testing.T) {
	tests := []struct {
		source string
		ctx    map[string]any
		want   bool
	}{
		{`ctx.app_id == "admin"`, map[string]any{"app_id": "admin"}, true},
		{`ctx.app_id == "admin"`, map[string]any{"app_id": "user"}, false},
		{`ctx.risk_score > 50`, map[string]any{"risk_score": float64(80)}, true},
		{`ctx.risk_score > 50`, map[string]any{"risk_score": float64(30)}, false},
		{`ctx.tool_name matches "shell*"`, map[string]any{"tool_name": "shell_exec"}, true},
		{`ctx.tool_name matches "shell*"`, map[string]any{"tool_name": "read_file"}, false},
		{`ctx.tool_name contains "read"`, map[string]any{"tool_name": "read_file"}, true},
		{`ctx.app_id == "admin" and ctx.risk_score > 50`, map[string]any{"app_id": "admin", "risk_score": float64(80)}, true},
		{`ctx.app_id == "admin" and ctx.risk_score > 50`, map[string]any{"app_id": "admin", "risk_score": float64(30)}, false},
		{`ctx.app_id == "admin" or ctx.app_id == "moderator"`, map[string]any{"app_id": "moderator"}, true},
		{`not ctx.app_id == "admin"`, map[string]any{"app_id": "user"}, true},
		{`ctx.tool_name in blocked_tools`, map[string]any{"tool_name": "shell", "blocked_tools": []string{"shell", "delete"}}, true},
		{`ctx.tool_name in blocked_tools`, map[string]any{"tool_name": "read", "blocked_tools": []string{"shell", "delete"}}, false},
		{`ctx.tool_name not_in allowed_tools`, map[string]any{"tool_name": "shell", "allowed_tools": []string{"read", "write"}}, true},
		{`"hello" starts_with "he"`, map[string]any{}, true},
		{`"hello" ends_with "lo"`, map[string]any{}, true},
		{`5 >= 3`, map[string]any{}, true},
		{`5 >= 5`, map[string]any{}, true},
		{`5 >= 7`, map[string]any{}, false},
		{`true`, map[string]any{}, true},
		{`false`, map[string]any{}, false},
		{`ctx.a == "x" and (ctx.b == "y" or ctx.c == "z")`, map[string]any{"a": "x", "b": "w", "c": "z"}, true},
		{`ctx.a == "x" and (ctx.b == "y" or ctx.c == "z")`, map[string]any{"a": "x", "b": "w", "c": "w"}, false},
		{`ctx.tool_name ~= "read_file"`, map[string]any{"tool_name": "shell"}, true},
		{`ctx.tool_name ~= "read_file"`, map[string]any{"tool_name": "read_file"}, false},
	}

	for _, tt := range tests {
		t.Run(tt.source, func(t *testing.T) {
			expr, err := CompileString(tt.source, "test")
			if err != nil {
				t.Fatalf("compile error: %v", err)
			}
			got, err := Eval(expr, tt.ctx)
			if err != nil {
				t.Fatalf("eval error: %v", err)
			}
			if got != tt.want {
				t.Errorf("Eval(%q, %v) = %v, want %v", tt.source, tt.ctx, got, tt.want)
			}
		})
	}
}

func TestJSONRoundTrip(t *testing.T) {
	source := `ctx.app_id == "admin" and ctx.risk_score > 50`
	expr, err := CompileString(source, "rule_001")
	if err != nil {
		t.Fatalf("compile error: %v", err)
	}

	data, err := json.Marshal(expr)
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}

	var restored Expression
	if err := json.Unmarshal(data, &restored); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}

	got, err := Eval(&restored, map[string]any{"app_id": "admin", "risk_score": float64(80)})
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if !got {
		t.Errorf("round-trip eval = false, want true")
	}
}

func TestJSONOutput(t *testing.T) {
	source := `ctx.tool_name matches "shell*" or ctx.tool_name == "delete" and ctx.risk_score > 50`
	expr, err := CompileString(source, "test")
	if err != nil {
		t.Fatalf("compile error: %v", err)
	}
	data, err := json.MarshalIndent(expr, "", "  ")
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}
	t.Logf("JSON IR:\n%s", string(data))

	// Verify it can be evaluated
	got, err := Eval(expr, map[string]any{
		"tool_name":  "shell_exec",
		"risk_score": float64(30),
	})
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if !got {
		t.Errorf("expected true for shell_exec")
	}
}
