/// MCP Proxy example: wraps an MCP Server with VirbiusAgent security pre-check.
///
/// Run with: cargo run --example mcp_proxy
/// The proxy intercepts tools/call requests, performs pre-check,
/// and only forwards allowed requests to the real MCP Server.
fn main() {
    println!("MCP Proxy example");
    println!("----------------");
    println!("In production, the proxy runs as a sidecar that intercepts MCP tool calls.");
    println!();
    println!("Flow:");
    println!("  1. Agent -> MCP Proxy (localhost:9090)");
    println!("  2. Proxy pre-checks (License + allowlist + args schema)");
    println!("  3. If allowed -> forward to MCP Server (localhost:8080)");
    println!("  4. If blocked -> return ToolError response");
    println!();
    println!("Integration code (Python):");
    println!("  from virbius_mcp_python import precheck_tool");
    println!("  result = precheck_tool(tool_name, args_json, license_jwt, pubkey, app_id)");
    println!("  if result['allowed']:");
    println!("      # execute tool");
    println!("  else:");
    println!("      raise Exception(f\"Tool blocked: {result['reason']}\")");
    println!();
    println!("Integration code (Node.js):");
    println!("  const { precheckTool } = require('virbius-mcp-node');");
    println!("  const result = precheckTool(toolName, argsJson, licenseJwt, pubkey, appId);");
    println!("  if (!result.allowed) throw new Error(`Tool blocked: ${result.reason}`);");
    println!();
}
