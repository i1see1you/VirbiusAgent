package expr

import (
	"fmt"
	"strconv"
	"strings"
)

// Eval evaluates a compiled expression against a context map and returns the boolean result.
//
// The context map should contain values for all variables referenced in the expression.
// Supported value types: string, float64, bool, and []string (for 'in' operations).
func Eval(expr *Expression, ctx map[string]any) (bool, error) {
	if len(expr.Nodes) == 0 {
		return false, fmt.Errorf("empty expression")
	}

	stack := make([]any, len(expr.Nodes))

	for i, n := range expr.Nodes {
		if i > expr.ResultNode && expr.ResultNode > 0 {
			break
		}

		var result any
		var err error

		switch n.Op {
		case OpLit:
			result = n.Val

		case OpVar:
			result = resolveVar(n.Val, ctx)

		case OpNot:
			if len(n.Args) != 1 {
				return false, fmt.Errorf("not: need 1 arg, got %d", len(n.Args))
			}
			b, ok := toBool(stack[n.Args[0]])
			if !ok {
				return false, fmt.Errorf("not: non-bool operand at node %d", n.Args[0])
			}
			result = !b

		case OpAnd:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("and: need 2 args, got %d", len(n.Args))
			}
			a, oka := toBool(stack[n.Args[0]])
			b, okb := toBool(stack[n.Args[1]])
			if !oka || !okb {
				return false, fmt.Errorf("and: non-bool operand")
			}
			result = a && b

		case OpOr:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("or: need 2 args, got %d", len(n.Args))
			}
			a, oka := toBool(stack[n.Args[0]])
			b, okb := toBool(stack[n.Args[1]])
			if !oka || !okb {
				return false, fmt.Errorf("or: non-bool operand")
			}
			result = a || b

		case OpEq:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("eq: need 2 args")
			}
			result = fmt.Sprintf("%v", stack[n.Args[0]]) == fmt.Sprintf("%v", stack[n.Args[1]])

		case OpNe:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("ne: need 2 args")
			}
			result = fmt.Sprintf("%v", stack[n.Args[0]]) != fmt.Sprintf("%v", stack[n.Args[1]])

		case OpGt, OpGe, OpLt, OpLe:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("%s: need 2 args", n.Op)
			}
			a, b, err := toFloatPair(stack[n.Args[0]], stack[n.Args[1]])
			if err != nil {
				return false, fmt.Errorf("%s: %w", n.Op, err)
			}
			switch n.Op {
			case OpGt:
				result = a > b
			case OpGe:
				result = a >= b
			case OpLt:
				result = a < b
			case OpLe:
				result = a <= b
			}

		case OpContains:
			if len(n.Args) != 1 {
				return false, fmt.Errorf("contains: need 1 arg")
			}
			s, ok := toString(stack[n.Args[0]])
			if !ok {
				return false, fmt.Errorf("contains: operand not a string")
			}
			result = strings.Contains(s, n.Val)

		case OpStartsWith:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("starts_with: need 2 args")
			}
			s, ok := toString(stack[n.Args[0]])
			if !ok {
				return false, fmt.Errorf("starts_with: left operand not a string")
			}
			prefix, ok := toString(stack[n.Args[1]])
			if !ok {
				return false, fmt.Errorf("starts_with: right operand not a string")
			}
			result = strings.HasPrefix(s, prefix)

		case OpEndsWith:
			if len(n.Args) != 2 {
				return false, fmt.Errorf("ends_with: need 2 args")
			}
			s, ok := toString(stack[n.Args[0]])
			if !ok {
				return false, fmt.Errorf("ends_with: left operand not a string")
			}
			suffix, ok := toString(stack[n.Args[1]])
			if !ok {
				return false, fmt.Errorf("ends_with: right operand not a string")
			}
			result = strings.HasSuffix(s, suffix)

		case OpMatches:
			if len(n.Args) != 1 {
				return false, fmt.Errorf("matches: need 1 arg")
			}
			s, ok := toString(stack[n.Args[0]])
			if !ok {
				return false, fmt.Errorf("matches: operand not a string")
			}
			result = matchWildcard(s, n.Val)

		case OpIn, OpNotIn:
			if len(n.Args) != 1 {
				return false, fmt.Errorf("%s: need 1 arg", n.Op)
			}
			val := fmt.Sprintf("%v", stack[n.Args[0]])
			// Look up the list from context
			listVal, ok := ctx[n.Val]
			if !ok {
				result = false
				break
			}
			list := toStringSlice(listVal)
			found := false
			for _, item := range list {
				if item == val {
					found = true
					break
				}
			}
			if n.Op == OpIn {
				result = found
			} else {
				result = !found
			}

		default:
			return false, fmt.Errorf("unknown op: %s", n.Op)
		}

		if err != nil {
			if n.Op != OpNot {
				return false, err
			}
		}
		stack[i] = result
	}

	res, ok := toBool(stack[expr.ResultNode])
	if !ok {
		return false, fmt.Errorf("result node %d did not produce a bool", expr.ResultNode)
	}
	return res, nil
}

// --- Helpers ---

func resolveVar(name string, ctx map[string]any) any {
	parts := strings.Split(name, ".")
	current := ctx
	for i, part := range parts {
		if i == len(parts)-1 {
			return current[part]
		}
		if sub, ok := current[part]; ok {
			if m, ok := sub.(map[string]any); ok {
				current = m
			} else {
				return nil
			}
		} else {
			return nil
		}
	}
	return nil
}

func toBool(v any) (bool, bool) {
	switch val := v.(type) {
	case bool:
		return val, true
	case string:
		return val == "true" || val == "1", true
	}
	return false, false
}

func toString(v any) (string, bool) {
	switch val := v.(type) {
	case string:
		return val, true
	case fmt.Stringer:
		return val.String(), true
	}
	return "", false
}

func toStringSlice(v any) []string {
	switch val := v.(type) {
	case []string:
		return val
	case []any:
		res := make([]string, 0, len(val))
		for _, item := range val {
			res = append(res, fmt.Sprintf("%v", item))
		}
		return res
	}
	return nil
}

func toFloatPair(a, b any) (float64, float64, error) {
	fa, err := toFloat(a)
	if err != nil {
		return 0, 0, fmt.Errorf("left operand: %w", err)
	}
	fb, err := toFloat(b)
	if err != nil {
		return 0, 0, fmt.Errorf("right operand: %w", err)
	}
	return fa, fb, nil
}

func toFloat(v any) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case string:
		return strconv.ParseFloat(val, 64)
	case int:
		return float64(val), nil
	case int64:
		return float64(val), nil
	}
	return 0, fmt.Errorf("cannot convert %T to float", v)
}

// matchWildcard implements wildcard pattern matching (* matches any characters).
func matchWildcard(s, pattern string) bool {
	parts := strings.Split(pattern, "*")
	if len(parts) == 1 {
		return s == pattern
	}
	// Must start with first part, end with last part, contain middle parts in order
	if !strings.HasPrefix(s, parts[0]) {
		return false
	}
	s = s[len(parts[0]):]
	for _, part := range parts[1 : len(parts)-1] {
		idx := strings.Index(s, part)
		if idx < 0 {
			return false
		}
		s = s[idx+len(part):]
	}
	return strings.HasSuffix(s, parts[len(parts)-1])
}
