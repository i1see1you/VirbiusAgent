from typing import Any, Optional, TypedDict

class PrecheckResult(TypedDict):
    allowed: bool
    reason: Optional[str]
    code: Optional[str]
    fast_path: bool
    sandbox_type: str

class LicenseInfo(TypedDict):
    app_id: str
    tenant_id: str
    allowed_tools: list[str]
    risk_quota: int
    expiry: int

class ConfigureResult(TypedDict):
    ok: bool
    mode: str
    sync_error: Optional[str]

class ScanResult(TypedDict, total=False):
    action: str
    effective_action: str
    trace_id: str
    max_risk_score: int
    rule_id: str
    rule_revision: int
    reason_code: str
    layer: int

class MemoryWriteResult(TypedDict):
    allowed: bool
    sanitized_content: str
    block_reason: Optional[str]
    pii_found: bool
    credential_detected: bool
    need_llm_check: bool

class MemoryReadResult(TypedDict):
    allowed: bool
    filtered_content: str
    block_reason: Optional[str]
    credential_detected: bool
    content_filtered: bool
    need_llm_check: bool

class RestoreResult(TypedDict):
    text: str
    unresolved_tokens: list[str]

def configure_rules(cfg_json: str) -> ConfigureResult: ...
def reload_rules() -> None: ...
def scan_edge(content: str, session_id: str) -> ScanResult: ...
def precheck_tool(
    tool_name: str, args_json: str, license_jwt: str, public_key_pem: str, app_id: str
) -> PrecheckResult: ...
def verify_license(jwt: str, public_key_pem: str, app_id: str) -> LicenseInfo: ...
def desensitize(text: str, trace_id: str, rules_json: Optional[str] = ...) -> str: ...
def restore(trace_id: str, session_id: str, text: str) -> RestoreResult: ...
def enhance_prompt(messages_json: str, context_json: str) -> str: ...
def intercept_memory_write(
    content: str, session_id: str, trace_id: str, tool_name: str
) -> MemoryWriteResult: ...
def intercept_memory_read(
    content: str, session_id: str, trace_id: str, tool_name: str
) -> MemoryReadResult: ...
def is_memory_write_tool(tool_name: str) -> bool: ...
def is_memory_read_tool(tool_name: str) -> bool: ...
