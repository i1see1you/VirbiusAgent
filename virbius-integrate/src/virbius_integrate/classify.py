from __future__ import annotations

from typing import Optional

RULE_TABLE_VERSION = "integrate-rules-v1"

HIGH_VERBS = (
    "delete", "drop", "truncate", "remove", "destroy",
    "exec", "execute", "shell", "subprocess", "bash", "command",
    "pay", "charge", "refund", "transfer", "withdraw",
    "send", "publish", "post", "deploy", "grant", "revoke",
    "write", "update", "insert", "create", "modify",
)

MEDIUM_VERBS = ("read", "query", "fetch", "get", "list", "search", "scan", "download", "upload")

DESTRUCTIVE_SCHEMA_KEYS = ("sql", "query", "command", "cmd", "script", "path", "file", "dir", "directory", "table", "collection", "bucket")
EGRESS_SCHEMA_KEYS = ("url", "domain", "webhook", "endpoint", "host", "recipient", "to", "email", "phone", "channel")
CREDENTIAL_SCHEMA_KEYS = ("token", "secret", "password", "credential", "api_key", "apikey", "apikey_id", "private_key")
SENSITIVE_DATA_KEYS = ("ssn", "id_card", "idcard", "phone", "mobile", "email", "address", "bank", "card_no", "salary", "health")


def classify_tool(name: str, description: str = "", args_keys: Optional[list] = None) -> dict:
    args_keys = [k.lower() for k in (args_keys or [])]
    haystack_name = (name or "").lower()
    haystack_desc = (description or "").lower()
    matched: list[str] = []
    risk = "low"
    confidence = "low"

    verb_hit = next((v for v in HIGH_VERBS if v in haystack_name), None)
    if verb_hit:
        matched.append(f"verb:{verb_hit}")
        risk = "high"
        confidence = "high"

    for key in DESTRUCTIVE_SCHEMA_KEYS:
        if key in args_keys:
            matched.append(f"schema:{key}")
            if risk != "high":
                risk = "medium"
                confidence = "medium"

    for key in EGRESS_SCHEMA_KEYS:
        if key in args_keys:
            matched.append(f"egress:{key}")
            if risk == "low":
                risk = "medium"
                confidence = "medium"
            elif risk == "medium":
                matched.append("chain-precursor")

    for key in CREDENTIAL_SCHEMA_KEYS:
        if key in args_keys:
            matched.append(f"credential:{key}")
            risk = "high"
            confidence = "high"

    for key in SENSITIVE_DATA_KEYS:
        if key in args_keys or key in haystack_desc:
            matched.append(f"sensitive:{key}")
            if risk == "low":
                risk = "medium"
                confidence = "medium"

    desc_lower = haystack_desc
    for phrase in ("irreversible", "cannot be undone", "destructive", "payment", "production database"):
        if phrase in desc_lower:
            matched.append(f"desc:{phrase.replace(' ', '_')}")
            risk = "high"
            confidence = "high"

    for verb in MEDIUM_VERBS:
        if verb in haystack_name and risk == "low":
            matched.append(f"read-verb:{verb}")

    recommended_mode = "B" if risk in ("high", "medium") else "A"
    return {
        "risk_hint": risk,
        "matched_rules": matched[:16],
        "confidence": confidence,
        "recommended_mode": recommended_mode,
        "rule_table_version": RULE_TABLE_VERSION,
    }


def _tool_args_keys(tool) -> list:
    try:
        schema = getattr(tool, "args_schema", None)
        if schema is not None and hasattr(schema, "model_fields"):
            return list(schema.model_fields.keys())
        args = getattr(tool, "args", None)
        if isinstance(args, dict):
            return [k for k in args.keys() if isinstance(k, str)]
    except Exception:
        pass
    return []


def _tool_fields(tool) -> tuple[str, str]:
    if isinstance(tool, dict):
        name = tool.get("tool_name") or tool.get("name") or ""
        description = tool.get("description") or ""
        return str(name), str(description)
    name = getattr(tool, "name", None) or getattr(tool, "__name__", "") or ""
    description = getattr(tool, "description", "") or ""
    return str(name), str(description)


def classify_tools(tools) -> list:
    rows = []
    for tool in tools or []:
        name, description = _tool_fields(tool)
        if not name:
            continue
        result = classify_tool(name, description, _tool_args_keys(tool))
        rows.append({
            "tool_name": name[:128],
            "description_digest": description[:200],
            **result,
        })
    return rows
