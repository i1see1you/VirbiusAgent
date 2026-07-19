package expr

import "testing"

func TestInferType_Literals(t *testing.T) {
	tests := []struct {
		source string
		want   ExprType
	}{
		{`true`, TypeBool},
		{`false`, TypeBool},
		{`"hello"`, TypeString},
		{`42`, TypeNumber},
		{`3.14`, TypeNumber},
	}
	for _, tt := range tests {
		ast, err := Parse(tt.source)
		if err != nil {
			t.Fatalf("Parse(%q) error: %v", tt.source, err)
		}
		got := InferType(ast)
		if got != tt.want {
			t.Errorf("InferType(%q) = %s, want %s", tt.source, got, tt.want)
		}
	}
}

func TestInferType_Variables(t *testing.T) {
	tests := []struct {
		source string
		want   ExprType
	}{
		{`ctx.app_id`, TypeUnknown},
		{`ctx.var('tool_name')`, TypeUnknown},
	}
	for _, tt := range tests {
		ast, err := Parse(tt.source)
		if err != nil {
			t.Fatalf("Parse(%q) error: %v", tt.source, err)
		}
		got := InferType(ast)
		if got != tt.want {
			t.Errorf("InferType(%q) = %s, want %s", tt.source, got, tt.want)
		}
	}
}

func TestInferType_BooleanExpressions(t *testing.T) {
	sources := []string{
		`ctx.app_id == "admin"`,
		`ctx.app_id ~= "admin"`,
		`ctx.score > 50`,
		`ctx.score >= 50`,
		`ctx.score < 50`,
		`ctx.score <= 50`,
		`ctx.app_id == "admin" and ctx.score > 50`,
		`ctx.app_id == "admin" or ctx.app_id == "mod"`,
		`not ctx.app_id == "admin"`,
		`ctx.tool_name matches "shell*"`,
		`ctx.tool_name contains "read"`,
		`ctx.tool_name in blocked_tools`,
		`ctx.tool_name not_in allowed_tools`,
		`"hello" starts_with "he"`,
		`"hello" ends_with "lo"`,
		`ctx.a == "x" and (ctx.b == "y" or ctx.c == "z")`,
	}
	for _, src := range sources {
		ast, err := Parse(src)
		if err != nil {
			t.Fatalf("Parse(%q) error: %v", src, err)
		}
		if !IsBooleanExpression(ast) {
			t.Errorf("IsBooleanExpression(%q) = false, want true (type=%s)", src, InferType(ast))
		}
	}
}

func TestInferType_NonBooleanExpressions(t *testing.T) {
	sources := []string{
		`"blocked"`,        // String literal
		`42`,                // Number literal
		`ctx.app_id`,        // Bare variable
		`ctx.var('tool')`,   // Bare ctx.var() call
	}
	for _, src := range sources {
		ast, err := Parse(src)
		if err != nil {
			t.Fatalf("Parse(%q) error: %v", src, err)
		}
		if IsBooleanExpression(ast) {
			t.Errorf("IsBooleanExpression(%q) = true, want false (type=%s)", src, InferType(ast))
		}
	}
}
