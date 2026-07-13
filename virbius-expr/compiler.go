package expr

import (
	"fmt"
	"strings"
)

type compiler struct {
	nodes []Node
	nextID int
}

// Compile converts a parsed AST node into a flat Expression IR.
func Compile(ast astNode, exprID, source string) (*Expression, error) {
	c := &compiler{}
	idx := c.compileNode(ast)
	if idx < 0 {
		return nil, fmt.Errorf("compilation failed")
	}
	return &Expression{
		Schema:     1,
		ExprID:     exprID,
		Source:     source,
		Nodes:      c.nodes,
		ResultNode: idx,
	}, nil
}

// CompileString parses and compiles a Lua expression string in one step.
func CompileString(source string, exprID string) (*Expression, error) {
	ast, err := Parse(source)
	if err != nil {
		return nil, fmt.Errorf("parse: %w", err)
	}
	return Compile(ast, exprID, source)
}

func (c *compiler) allocID() int {
	id := c.nextID
	c.nextID++
	return id
}

func (c *compiler) addNode(n Node) int {
	n.ID = c.allocID()
	c.nodes = append(c.nodes, n)
	return n.ID
}

func (c *compiler) compileNode(n astNode) int {
	if n == nil {
		return -1
	}
	switch v := n.(type) {
	case astVariable:
		return c.addNode(Node{
			Op:  OpVar,
			Val: strings.Join(v.Parts, "."),
		})

	case astString:
		return c.addNode(Node{
			Op:  OpLit,
			Val: v.Value,
		})

	case astNumber:
		s := fmt.Sprintf("%v", v.Value)
		return c.addNode(Node{
			Op:  OpLit,
			Val: s,
		})

	case astBool:
		s := "false"
		if v.Value {
			s = "true"
		}
		return c.addNode(Node{
			Op:  OpLit,
			Val: s,
		})

	case astUnary:
		right := c.compileNode(v.Right)
		if right < 0 {
			return -1
		}
		switch v.Op {
		case "not":
			return c.addNode(Node{Op: OpNot, Args: []int{right}})
		}
		return -1

	case astBinary:
		left := c.compileNode(v.Left)
		right := c.compileNode(v.Right)
		if left < 0 || right < 0 {
			return -1
		}
		op := mapBinaryOp(v.Op)
		if op == "" {
			return -1
		}
		return c.addNode(Node{Op: op, Args: []int{left, right}})

	case astMatches:
		left := c.compileNode(v.Left)
		if left < 0 {
			return -1
		}
		return c.addNode(Node{
			Op:   OpMatches,
			Args: []int{left},
			Val:  v.Pattern,
		})

	case astContains:
		left := c.compileNode(v.Left)
		if left < 0 {
			return -1
		}
		return c.addNode(Node{
			Op:   OpContains,
			Args: []int{left},
			Val:  v.Substr,
		})

	case astIn:
		left := c.compileNode(v.Left)
		if left < 0 {
			return -1
		}
		op := OpIn
		if v.Op == "not_in" {
			op = OpNotIn
		}
		return c.addNode(Node{
			Op:   op,
			Args: []int{left},
			Val:  v.ListName,
		})
	}
	return -1
}

func mapBinaryOp(op string) OpCode {
	switch op {
	case "==":
		return OpEq
	case "~=":
		return OpNe
	case ">":
		return OpGt
	case ">=":
		return OpGe
	case "<":
		return OpLt
	case "<=":
		return OpLe
	case "and":
		return OpAnd
	case "or":
		return OpOr
	case "starts_with":
		return OpStartsWith
	case "ends_with":
		return OpEndsWith
	}
	return ""
}
