package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/virbius/virbius-expr"
)

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
		default:
			source = args[i]
		}
	}

	if source == "" {
		fmt.Fprintf(os.Stderr, "no expression provided\n")
		os.Exit(1)
	}

	expr, err := expr.CompileString(source, exprID)
	if err != nil {
		fmt.Fprintf(os.Stderr, "compilation error: %v\n", err)
		os.Exit(1)
	}

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
