package expr

// OpCode defines the set of supported operators in the expression IR.
type OpCode string

const (
	// Terminals
	OpVar OpCode = "var"
	OpLit OpCode = "lit"

	// Comparison
	OpEq OpCode = "eq"
	OpNe OpCode = "ne"
	OpGt OpCode = "gt"
	OpGe OpCode = "ge"
	OpLt OpCode = "lt"
	OpLe OpCode = "le"

	// String operations
	OpContains   OpCode = "contains"
	OpStartsWith OpCode = "starts_with"
	OpEndsWith   OpCode = "ends_with"
	OpMatches    OpCode = "matches"

	// Logical
	OpAnd OpCode = "and"
	OpOr  OpCode = "or"
	OpNot OpCode = "not"

	// Set membership
	OpIn    OpCode = "in"
	OpNotIn OpCode = "not_in"
)

// Node is a single node in the flat operator DAG.
// Each node references child nodes by their index in the owning Nodes slice.
type Node struct {
	ID   int     `json:"id"`
	Op   OpCode  `json:"op"`
	Val  string  `json:"val,omitempty"`
	Args []int   `json:"args,omitempty"`
}

// Expression is a compiled expression ready for evaluation.
type Expression struct {
	Schema     int    `json:"schema_version"`
	ExprID     string `json:"expr_id,omitempty"`
	Source     string `json:"source,omitempty"`
	Nodes      []Node `json:"nodes"`
	ResultNode int    `json:"result_node"`
}

// ActionBinding specifies what to do when an expression evaluates to true.
type ActionBinding struct {
	ExprID    string `json:"expr_id"`
	Action    string `json:"action"`
	RuleID    string `json:"rule_id"`
	Reason    string `json:"reason"`
	RiskScore int    `json:"risk_score"`
}

// CompiledRule combines a compiled expression with its action binding.
type CompiledRule struct {
	Expression Expression    `json:"expression"`
	Action     ActionBinding `json:"action"`
}
