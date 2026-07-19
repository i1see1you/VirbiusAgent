package main

import (
	"strings"
	"testing"
)

func TestCompileLuaScript_Valid(t *testing.T) {
	scripts := []string{
		// Simple boolean return
		`function decide(ctx)
  return true
end`,
		// Comparison expression
		`function decide(ctx)
  return ctx.app_id == "admin"
end`,
		// Complex boolean expression
		`function decide(ctx)
  return ctx.app_id == "admin" and ctx.risk_score > 50
end`,
		// Multiple return branches — all boolean
		`function decide(ctx)
  if ctx.wouldHitBlock() then return true end
  if ctx.app_id == "admin" then return true end
  return false
end`,
		// ctx.var() in a comparison
		`function decide(ctx)
  return ctx.var('tool_name') == "shell_exec"
end`,
		// not expression
		`function decide(ctx)
  return not ctx.app_id == "admin"
end`,
		// matches / contains / in
		`function decide(ctx)
  return ctx.tool_name matches "shell*" or ctx.tool_name in blocked_tools
end`,
	}
	for i, script := range scripts {
		expr, err := CompileLuaScript(script, "test")
		if err != nil {
			t.Errorf("script #%d: unexpected error: %v\nscript:\n%s", i+1, err, script)
			continue
		}
		if expr == nil {
			t.Errorf("script #%d: got nil expression", i+1)
		}
	}
}

func TestCompileLuaScript_MissingFunctionSignature(t *testing.T) {
	scripts := []string{
		// No function at all
		`return true`,
		// Wrong function name
		`function check(ctx)
  return true
end`,
		// Wrong parameter name
		`function decide(context)
  return true
end`,
		// No parameter
		`function decide()
  return true
end`,
	}
	for i, script := range scripts {
		_, err := CompileLuaScript(script, "test")
		if err == nil {
			t.Errorf("script #%d: expected error for missing function signature\nscript:\n%s", i+1, script)
			continue
		}
		if !strings.Contains(err.Error(), "must define") {
			t.Errorf("script #%d: error should mention function signature, got: %v", i+1, err)
		}
	}
}

func TestCompileLuaScript_NoReturnStatement(t *testing.T) {
	script := `function decide(ctx)
  local x = ctx.var('tool_name')
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for missing return statement")
	}
	if !strings.Contains(err.Error(), "no `return") {
		t.Errorf("error should mention missing return, got: %v", err)
	}
}

func TestCompileLuaScript_ReturnStringLiteral(t *testing.T) {
	script := `function decide(ctx)
  return "blocked"
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for returning string literal")
	}
	if !strings.Contains(err.Error(), "must return boolean") {
		t.Errorf("error should mention boolean requirement, got: %v", err)
	}
	if !strings.Contains(err.Error(), "string") {
		t.Errorf("error should mention string type, got: %v", err)
	}
}

func TestCompileLuaScript_ReturnNumberLiteral(t *testing.T) {
	script := `function decide(ctx)
  return 100
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for returning number literal")
	}
	if !strings.Contains(err.Error(), "must return boolean") {
		t.Errorf("error should mention boolean requirement, got: %v", err)
	}
	if !strings.Contains(err.Error(), "number") {
		t.Errorf("error should mention number type, got: %v", err)
	}
}

func TestCompileLuaScript_ReturnBareVariable(t *testing.T) {
	script := `function decide(ctx)
  return ctx.app_id
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for returning bare variable (unknown type)")
	}
	if !strings.Contains(err.Error(), "must return boolean") {
		t.Errorf("error should mention boolean requirement, got: %v", err)
	}
	if !strings.Contains(err.Error(), "unknown") {
		t.Errorf("error should mention unknown type, got: %v", err)
	}
}

func TestCompileLuaScript_ReturnBareCtxVar(t *testing.T) {
	script := `function decide(ctx)
  return ctx.var('tool_name')
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for returning bare ctx.var() call (unknown type)")
	}
	if !strings.Contains(err.Error(), "must return boolean") {
		t.Errorf("error should mention boolean requirement, got: %v", err)
	}
}

func TestCompileLuaScript_OneBranchNonBoolean(t *testing.T) {
	script := `function decide(ctx)
  if ctx.wouldHitBlock() then return true end
  return "blocked"
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for non-boolean return in one branch")
	}
	if !strings.Contains(err.Error(), "return #2") {
		t.Errorf("error should mention return #2, got: %v", err)
	}
	if !strings.Contains(err.Error(), "string") {
		t.Errorf("error should mention string type, got: %v", err)
	}
}

func TestCompileLuaScript_ReturnNumberInOneBranch(t *testing.T) {
	script := `function decide(ctx)
  if ctx.score > 50 then return 100 end
  return false
end`
	_, err := CompileLuaScript(script, "test")
	if err == nil {
		t.Fatal("expected error for numeric return in one branch")
	}
	if !strings.Contains(err.Error(), "return #1") {
		t.Errorf("error should mention return #1, got: %v", err)
	}
	if !strings.Contains(err.Error(), "number") {
		t.Errorf("error should mention number type, got: %v", err)
	}
}

func TestCompileLuaScript_CompilesFirstReturnIntoIR(t *testing.T) {
	script := `function decide(ctx)
  if ctx.wouldHitBlock() then return true end
  return false
end`
	expr, err := CompileLuaScript(script, "rule_001")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if expr.ExprID != "rule_001" {
		t.Errorf("ExprID = %q, want %q", expr.ExprID, "rule_001")
	}
	if len(expr.Nodes) == 0 {
		t.Error("expected non-empty nodes")
	}
}
