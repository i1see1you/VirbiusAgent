package expr

import (
	"fmt"
	"strconv"
	"strings"
	"text/scanner"
)

// --- AST Node Types ---

type astNode interface {
	astMarker()
}

type (
	astVariable struct{ Parts []string }
	astString   struct{ Value string }
	astNumber   struct{ Value float64 }
	astBool     struct{ Value bool }
	astUnary    struct{ Op string; Right astNode }
	astBinary   struct{ Left, Right astNode; Op string }
	astMatches  struct{ Left astNode; Pattern string }
	astContains struct{ Left astNode; Substr string }
	astIn       struct{ Left astNode; ListName string; Op string }
	astCall     struct{ Name string; Arg string }
)

func (astVariable) astMarker() {}
func (astString) astMarker()   {}
func (astNumber) astMarker()   {}
func (astBool) astMarker()     {}
func (astUnary) astMarker()    {}
func (astBinary) astMarker()   {}
func (astMatches) astMarker()  {}
func (astContains) astMarker() {}
func (astIn) astMarker()       {}
func (astCall) astMarker()     {}

// --- Pratt Parser ---

type parser struct {
	scan   scanner.Scanner
	peek   rune
	pos    int
	idents []string
}

type bp int

const (
	bpLowest  bp = 0
	bpOr         = 10
	bpAnd        = 20
	bpNot        = 30
	bpCmp        = 40
	bpStringOp   = 50
	bpPrimary    = 80
	bpPrefix     = 90
)

// Precedence table for infix operators.
var infixBP = map[string]bp{
	"or":            bpOr,
	"and":           bpAnd,
	"==":            bpCmp,
	"~=":            bpCmp,
	">":             bpCmp,
	">=":            bpCmp,
	"<":             bpCmp,
	"<=":            bpCmp,
	"matches":       bpStringOp,
	"contains":      bpStringOp,
	"starts_with":   bpStringOp,
	"ends_with":     bpStringOp,
	"in":            bpStringOp,
	"not_in":        bpStringOp,
}

// Parse parses a Lua expression string into an AST node.
func Parse(source string) (astNode, error) {
	var p parser
	p.scan.Init(strings.NewReader(source))
	p.scan.Mode = scanner.ScanIdents | scanner.ScanStrings | scanner.ScanInts | scanner.ScanFloats | scanner.ScanChars
	p.scan.Error = func(s *scanner.Scanner, msg string) {}
	p.next()
	result := p.parseExpr(bpLowest)
	if p.peek != scanner.EOF {
		return nil, fmt.Errorf("unexpected token %q at position %d", scanner.TokenString(p.peek), p.pos)
	}
	return result, nil
}

func (p *parser) next() {
	p.peek = p.scan.Scan()
	p.pos = p.scan.Position.Column
}

func (p *parser) tokenText() string {
	return p.scan.TokenText()
}

// parseExpr is the main Pratt parsing loop.
func (p *parser) parseExpr(minBP bp) astNode {
	left := p.parsePrefix()
	if left == nil {
		return nil
	}

	for p.peek != scanner.EOF {
		// Check for binary/infix operators
		op := p.tokenText()

		// Combine two-character operators: ==, ~=, >=, <=
		// text/scanner scans each char separately, so we peek ahead
		isMultiCharOp := false
		if p.peek == '=' || p.peek == '~' || p.peek == '>' || p.peek == '<' {
			if p.scan.Peek() == '=' {
				op = string(p.peek) + "="
				isMultiCharOp = true
			}
		}

		bp, ok := infixBP[op]
		if !ok || bp < minBP {
			break
		}
		p.next()  // consume first char of operator
		if isMultiCharOp {
			p.next() // consume second char (=)
		}

		switch op {
		case "matches":
			right := p.parseExpr(bp)
			if right == nil {
				return nil
			}
			s, ok := right.(astString)
			if !ok {
				return nil
			}
			left = astMatches{Left: left, Pattern: s.Value}

		case "contains":
			right := p.parseExpr(bp)
			if right == nil {
				return nil
			}
			s, ok := right.(astString)
			if !ok {
				return nil
			}
			left = astContains{Left: left, Substr: s.Value}

		case "starts_with":
			right := p.parseExpr(bp)
			if right == nil {
				return nil
			}
			s, ok := right.(astString)
			if !ok {
				return nil
			}
			left = astBinary{Left: left, Right: astString{Value: s.Value}, Op: "starts_with"}

		case "ends_with":
			right := p.parseExpr(bp)
			if right == nil {
				return nil
			}
			s, ok := right.(astString)
			if !ok {
				return nil
			}
			left = astBinary{Left: left, Right: astString{Value: s.Value}, Op: "ends_with"}

		case "in", "not_in":
			right := p.parseExpr(bp)
			if right == nil {
				return nil
			}
			v, ok := right.(astVariable)
			if !ok {
				return nil
			}
			name := strings.Join(v.Parts, ".")
			left = astIn{Left: left, ListName: name, Op: op}

		default:
			right := p.parseExpr(bp)
			if right == nil {
				return nil
			}
			left = astBinary{Left: left, Right: right, Op: op}
		}
	}

	return left
}

// parsePrefix handles prefix expressions (literals, variables, unary, parentheses).
func (p *parser) parsePrefix() astNode {
	switch p.peek {
	case scanner.Ident:
		tok := p.tokenText()
		switch tok {
		case "true":
			p.next()
			return astBool{Value: true}
		case "false":
			p.next()
			return astBool{Value: false}
		case "not":
			p.next()
			right := p.parseExpr(bpNot)
			if right == nil {
				return nil
			}
			return astUnary{Op: "not", Right: right}
		default:
			// Variable: read dotted name
			var parts []string
			for {
				parts = append(parts, tok)
				p.next()
				if p.peek != '.' {
					break
				}
				p.next() // consume '.'
				if p.peek != scanner.Ident {
					return nil
				}
				tok = p.tokenText()
			}
			// Check for function call: ident '(' string_literal ')'
			if p.peek == '(' {
				p.next() // consume '('
			if p.peek != scanner.String && p.peek != scanner.Char {
				return nil
			}
				argRaw := p.tokenText()
				p.next()
				arg := argRaw
				if len(arg) >= 2 {
					arg = arg[1 : len(arg)-1]
				}
				if p.peek != ')' {
					return nil
				}
				p.next() // consume ')'
				return astCall{Name: strings.Join(parts, "."), Arg: arg}
			}
			return astVariable{Parts: parts}
		}

	case scanner.String, scanner.Char:
		raw := p.tokenText()
		p.next()
		// Remove quotes (single or double)
		s := raw
		if len(s) >= 2 {
			s = s[1 : len(s)-1]
		}
		return astString{Value: s}

	case scanner.Int, scanner.Float:
		raw := p.tokenText()
		p.next()
		v, err := strconv.ParseFloat(raw, 64)
		if err != nil {
			return nil
		}
		return astNumber{Value: v}

	case '(':
		p.next() // consume '('
		expr := p.parseExpr(bpLowest)
		if p.peek != ')' {
			return nil
		}
		p.next() // consume ')'
		return expr

	default:
		return nil
	}
}

// --- Type Inference ---

// ExprType represents the inferred static type of an expression.
type ExprType string

const (
	TypeUnknown ExprType = "unknown"
	TypeBool    ExprType = "bool"
	TypeString  ExprType = "string"
	TypeNumber  ExprType = "number"
)

// InferType infers the static type of an AST node.
//
// Type rules:
//   - Literals: astBool→bool, astString→string, astNumber→number
//   - Variables (ctx.app_id) and calls (ctx.var('x')) → unknown (runtime-dependent)
//   - Comparisons (==, ~=, >, >=, <, <=) → bool
//   - Logical (and, or, not) → bool
//   - String ops (matches, contains, starts_with, ends_with) → bool
//   - Set membership (in, not_in) → bool
func InferType(node astNode) ExprType {
	switch v := node.(type) {
	case astBool:
		return TypeBool
	case astString:
		return TypeString
	case astNumber:
		return TypeNumber
	case astVariable:
		return TypeUnknown
	case astCall:
		return TypeUnknown // ctx.var() returns a runtime value
	case astUnary:
		if v.Op == "not" {
			return TypeBool
		}
		return TypeUnknown
	case astBinary:
		switch v.Op {
		case "==", "~=", ">", ">=", "<", "<=":
			return TypeBool
		case "and", "or":
			return TypeBool
		case "starts_with", "ends_with":
			return TypeBool
		}
		return TypeUnknown
	case astMatches:
		return TypeBool
	case astContains:
		return TypeBool
	case astIn:
		return TypeBool
	}
	return TypeUnknown
}

// IsBooleanExpression returns true if the expression is guaranteed to
// evaluate to a boolean at runtime.
func IsBooleanExpression(node astNode) bool {
	return InferType(node) == TypeBool
}
