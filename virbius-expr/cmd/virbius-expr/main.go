package main

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"strings"

	"github.com/virbius/virbius-expr"
)

// returnExprPattern matches a `return <expr>` statement in a Lua script.
// It captures the expression after `return` up to the end of line.
var returnExprPattern = regexp.MustCompile(`(?m)^\s*return\s+(.+?)\s*$`)

// CompileLuaScript extracts the `return <expr>` from a Lua decide(ctx) script
// and compiles the expression into IR JSON. Returns the compiled Expression
// or an error if no return statement is found or compilation fails.
func CompileLuaScript(script string, exprID string) (*expr.Expression, error) {
	m := returnExprPattern.FindStringSubmatch(script)
	if m == nil {
		return nil, fmt.Errorf("no `return <expr>` statement found in script")
	}
	source := strings.TrimSpace(m[1])
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
	expr := expr2

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
			Expression: *expr,
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
		data, _ = json.MarshalIndent(expr, "", "  ")
	}

	fmt.Println(string(data))
}
