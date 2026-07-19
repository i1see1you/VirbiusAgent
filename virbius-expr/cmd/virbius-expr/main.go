package main

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/virbius/virbius-expr"
)

// returnExprPattern matches all `return <expr>` statements in a Lua script,
// including inline returns like `if x then return true end`.
// It captures the expression after `return` up to `end` keyword or end of line.
var returnExprPattern = regexp.MustCompile(`(?m)\breturn\s+([^;\n]+?)(?:\s+end\b|\s*$)`)

// funcSignaturePattern validates that the script defines `function decide(ctx)`.
var funcSignaturePattern = regexp.MustCompile(`(?m)function\s+decide\s*\(\s*ctx\s*\)`)

// CompileLuaScript validates and compiles a Lua `decide(ctx)` script.
//
// Performs three validation steps:
//  1. Function signature: script must define `function decide(ctx) ... end`
//  2. Return type: every `return <expr>` must evaluate to boolean
//  3. Compilation: the first return expression is compiled into IR JSON
//
// Returns the compiled Expression or an error if validation fails.
func CompileLuaScript(script string, exprID string) (*expr.Expression, error) {
	// Step 1: Validate function signature
	if !funcSignaturePattern.MatchString(script) {
		return nil, fmt.Errorf("script must define `function decide(ctx) ... end`")
	}

	// Step 2: Extract all return statements and validate their types
	matches := returnExprPattern.FindAllStringSubmatch(script, -1)
	if len(matches) == 0 {
		return nil, fmt.Errorf("no `return <expr>` statement found in decide(ctx)")
	}

	for i, m := range matches {
		retSource := strings.TrimSpace(m[1])
		ast, err := expr.Parse(retSource)
		if err != nil {
			return nil, fmt.Errorf("return #%d: parse error: %w", i+1, err)
		}
		if !expr.IsBooleanExpression(ast) {
			typ := expr.InferType(ast)
			return nil, fmt.Errorf(
				"return #%d: decide(ctx) must return boolean, got %s — "+
					"intent/risk come from rule row config, not script return value",
				i+1, typ)
		}
	}

	// Step 3: Compile the first return expression into IR
	source := strings.TrimSpace(matches[0][1])
	return expr.CompileString(source, exprID)
}

func main() {
	args := os.Args[1:]
	if len(args) < 1 {
		fmt.Fprintf(os.Stderr, "Usage: virbius-expr [--id <expr_id>] <lua_expression>\n")
		fmt.Fprintf(os.Stderr, "       virbius-expr --stdin [--id <expr_id>]\n")
		fmt.Fprintf(os.Stderr, "       virbius-expr --file <path> [--id <expr_id>]\n")
		os.Exit(1)
	}

	exprID := "default"
	source := ""
	scriptMode := false

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--id":
			if i+1 < len(args) {
				i++
				exprID = args[i]
			}
		case "--stdin":
			data, err := os.ReadFile("/dev/stdin")
			if err != nil {
				fmt.Fprintf(os.Stderr, "error reading stdin: %v\n", err)
				os.Exit(1)
			}
			source = strings.TrimSpace(string(data))
		case "--file":
			if i+1 < len(args) {
				i++
				data, err := os.ReadFile(args[i])
				if err != nil {
					fmt.Fprintf(os.Stderr, "error reading file: %v\n", err)
					os.Exit(1)
				}
				source = strings.TrimSpace(string(data))
			}
		case "--script":
			scriptMode = true
		default:
			source = args[i]
		}
	}

	if source == "" {
		fmt.Fprintf(os.Stderr, "no expression provided\n")
		os.Exit(1)
	}

	var expr2 *expr.Expression
	var err error
	if scriptMode {
		expr2, err = CompileLuaScript(source, exprID)
	} else {
		expr2, err = expr.CompileString(source, exprID)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "compilation error: %v\n", err)
		os.Exit(1)
	}
	expression := expr2

	// Support wrapping in a CompiledRule for direct use in Wasm config
	shouldWrap := false
	for _, a := range args {
		if a == "--with-action" || a == "--wrap" {
			shouldWrap = true
			break
		}
	}

	var data []byte
	if shouldWrap {
		// Parse action flags
		action := "block"
		ruleID := exprID
		reason := "expression matched"
		riskScore := 0
		for i := 0; i < len(args); i++ {
			switch args[i] {
			case "--action":
				if i+1 < len(args) {
					i++
					action = args[i]
				}
			case "--rule-id":
				if i+1 < len(args) {
					i++
					ruleID = args[i]
				}
			case "--reason":
				if i+1 < len(args) {
					i++
					reason = args[i]
				}
			case "--risk-score":
				if i+1 < len(args) {
					i++
					fmt.Sscanf(args[i], "%d", &riskScore)
				}
			}
		}
		rule := expr.CompiledRule{
			Expression: *expression,
			Action: expr.ActionBinding{
				ExprID:    exprID,
				Action:    action,
				RuleID:    ruleID,
				Reason:    reason,
				RiskScore: riskScore,
			},
		}
		data, _ = json.MarshalIndent(rule, "", "  ")
	} else {
		data, _ = json.MarshalIndent(expression, "", "  ")
	}

	fmt.Println(string(data))
}
