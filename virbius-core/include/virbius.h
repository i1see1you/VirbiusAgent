/**
 * virbius.h — C ABI for virbius-core (VirbiusAgent Edge SDK)
 *
 * Provides FFI access to core security functions:
 *   - Initialization / manifest loading
 *   - Content scanning (DLP / keyword matching)
 *   - License verification (Ed25519 JWT)
 *   - Tool pre-check (allowlist + JSON Schema)
 *   - Prompt enhancement (constitution injection + PII desensitization)
 *
 * Thread safety: All functions are thread-safe after initialization.
 * Memory management: Strings returned in result structs or as return values
 *   must be freed with virbius_free_string().
 *
 * Usage example (C):
 *
 *   #include "virbius.h"
 *   #include <stdio.h>
 *
 *   int main() {
 *       // 1. Initialize (from config JSON or control URL)
 *       if (virbius_init("https://control.example.com") != 0) {
 *           fprintf(stderr, "init failed\n");
 *           return 1;
 *       }
 *
 *       // 2. Verify License
 *       VirbiusLicenseInfo lic;
 *       if (virbius_verify_license(jwt, pubkey_pem, app_id, &lic) != 0) {
 *           fprintf(stderr, "license invalid\n");
 *           return 1;
 *       }
 *       printf("app_id=%s, risk_quota=%u\n", lic.app_id, lic.risk_quota);
 *       virbius_free_string((char*)lic.app_id);
 *       virbius_free_string((char*)lic.tenant_id);
 *       virbius_free_string((char*)lic.allowed_tools_json);
 *
 *       // 3. Pre-check tool call
 *       VirbiusPrecheckResult pc;
 *       virbius_precheck("read_file", "{\"path\":\"/tmp\"}", jwt, pubkey_pem, app_id, &pc);
 *       if (pc.allowed) {
 *           // execute tool
 *       }
 *       virbius_free_string((char*)pc.reason);
 *       virbius_free_string((char*)pc.sandbox_type);
 *
 *       // 4. Enhance prompt
 *       const char* enhanced = virbius_enhance_prompt(messages_json, context_json);
 *       if (enhanced) {
 *           // use enhanced messages
 *           virbius_free_string((char*)enhanced);
 *       }
 *
 *       return 0;
 *   }
 */

#ifndef VIRBIUS_H
#define VIRBIUS_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

/* =========================================================================
 * Types
 * ========================================================================= */

/** Scan context for content scanning (virbius_scan). */
typedef struct {
    const char *user_id;    /**< Optional: user identifier (NULL = anonymous) */
    const char *device_id;  /**< Optional: device identifier (NULL = unknown) */
    const char *scene;      /**< Scene name (NULL = "default") */
    const char *trace_id;   /**< Optional: trace ID (NULL = auto-generated) */
} VirbiusScanCtx;

/** Effective action returned by virbius_scan. */
typedef enum {
    VIRBIUS_ACTION_ALLOW = 0,   /**< Content is allowed */
    VIRBIUS_ACTION_BLOCK = 1,   /**< Content is blocked */
} VirbiusAction;

/** Result of virbius_scan (content scanning). */
typedef struct {
    VirbiusAction action;       /**< Allow or Block */
    const char *rule_id;        /**< Rule ID on block (NULL on allow) */
    int rule_revision;          /**< Rule revision (0 on allow) */
    const char *reason_code;    /**< Reason code on block (NULL on allow) */
    const char *layer;          /**< Layer that blocked (NULL on allow) */
    const char *trace_id;       /**< Always non-NULL; free with virbius_free_string */
} VirbiusScanResult;

/** Result of virbius_precheck (tool pre-check). */
typedef struct {
    int allowed;                /**< 1 = allowed, 0 = denied */
    const char *reason;         /**< NULL when allowed; otherwise denial reason */
    int fast_path;              /**< 1 = qualifies for fast path (skip engine) */
    const char *sandbox_type;   /**< Sandbox type ("none", "landlock", etc.) */
} VirbiusPrecheckResult;

/** License claims returned by virbius_verify_license. */
typedef struct {
    const char *app_id;             /**< Application ID */
    const char *tenant_id;          /**< Tenant ID */
    const char *allowed_tools_json; /**< JSON array of allowed tools (e.g. ["read_file","search"]) */
    unsigned int risk_quota;        /**< Maximum session risk score */
    long long expiry;               /**< Expiration timestamp (Unix epoch) */
} VirbiusLicenseInfo;

/* =========================================================================
 * Initialization
 * ========================================================================= */

/**
 * Initialize virbius-core from a control URL or offline manifest path.
 *
 * @param manifest_url  Control base URL (https://...) or offline manifest file path.
 *                      Pass NULL to use installed configuration or environment.
 * @return 0 on success, -1 on failure.
 */
int virbius_init(const char *manifest_url);

/**
 * Initialize virbius-core from a JSON configuration string.
 *
 * The JSON must match the EdgeInitConfig schema:
 *   {
 *     "control_base_url": "https://control.example.com",
 *     "offline_manifest_path": "/path/to/manifest.json",
 *     "tenant_id": "default",
 *     "app_id": "my-agent",
 *     "cache_dir": "/var/cache/virbius"
 *   }
 *
 * @param json  JSON configuration string (nul-terminated).
 * @return 0 on success, -1 on failure.
 */
int virbius_init_config_json(const char *json);

/**
 * Reload manifest and rules from the configured source.
 * Call after rules are updated in virbius-control to pick up changes.
 *
 * @return Always 0.
 */
int virbius_reload(void);

/* =========================================================================
 * Content scanning (DLP / keyword matching)
 * ========================================================================= */

/**
 * Scan content against edge rules (DLP, keyword deny lists).
 *
 * @param ctx   Scan context (may be NULL for defaults).
 * @param text  Content to scan (nul-terminated, must not be empty).
 * @param out   Pointer to receive result. Caller must free strings via virbius_free_string.
 * @return 0 on success, -1 on invalid input.
 */
int virbius_scan(const VirbiusScanCtx *ctx, const char *text, VirbiusScanResult *out);

/* =========================================================================
 * License verification
 * ========================================================================= */

/**
 * Verify a License JWT (Ed25519 signed) and extract claims.
 *
 * @param jwt            License JWT string (nul-terminated).
 * @param public_key_pem Ed25519 public key in PEM format (nul-terminated).
 * @param app_id         Expected app_id (must match JWT claims).
 * @param out            Pointer to receive license info. Caller must free all
 *                       string fields via virbius_free_string.
 * @return 0 on success, -1 on verification failure or invalid input.
 */
int virbius_verify_license(const char *jwt,
                           const char *public_key_pem,
                           const char *app_id,
                           VirbiusLicenseInfo *out);

/* =========================================================================
 * Tool pre-check
 * ========================================================================= */

/**
 * Pre-check a tool call: License allowlist + JSON Schema validation.
 *
 * @param tool_name      Tool name to check (nul-terminated).
 * @param args_json      Tool arguments as JSON string (nul-terminated).
 * @param license_jwt    License JWT (nul-terminated).
 * @param public_key_pem Ed25519 public key PEM (nul-terminated).
 * @param app_id         App ID for License verification (nul-terminated).
 * @param out            Pointer to receive result. Caller must free
 *                       `reason` and `sandbox_type` via virbius_free_string.
 * @return 0 on success (check out.allowed), -1 on error (License invalid, bad JSON).
 */
int virbius_precheck(const char *tool_name,
                     const char *args_json,
                     const char *license_jwt,
                     const char *public_key_pem,
                     const char *app_id,
                     VirbiusPrecheckResult *out);

/* =========================================================================
 * Prompt enhancement
 * ========================================================================= */

/**
 * Enhance a prompt with constitution injection and PII desensitization.
 *
 * Injects constitutional rules as a system message prefix, adds dynamic context
 * suffix (recent tool activity), and desensitizes PII in user/assistant messages.
 *
 * @param messages_json  JSON array of message strings (nul-terminated).
 *                       Each message is a JSON-serialized chat message object
 *                       (e.g. {"role":"user","content":"hello"}).
 * @param context_json   Enhancement context JSON (nul-terminated):
 *                       {
 *                         "app_id": "my-agent",
 *                         "session_id": "sess-123",
 *                         "scene": "chat",
 *                         "risk_score": 0,
 *                         "license_tools": ["read_file", "search"],
 *                         "constitution_version": "v1"
 *                       }
 * @return Heap-allocated JSON array string of enhanced messages on success,
 *         NULL on error. Caller MUST free with virbius_free_string.
 */
const char *virbius_enhance_prompt(const char *messages_json,
                                   const char *context_json);

/* =========================================================================
 * Memory management
 * ========================================================================= */

/**
 * Free a string allocated by virbius-core C ABI.
 *
 * Use this to free:
 *   - VirbiusScanResult fields (trace_id, rule_id, reason_code, layer)
 *   - VirbiusPrecheckResult fields (reason, sandbox_type)
 *   - VirbiusLicenseInfo fields (app_id, tenant_id, allowed_tools_json)
 *   - Return value of virbius_enhance_prompt
 *
 * @param p  Pointer to free (NULL is a safe no-op).
 */
void virbius_free_string(char *p);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* VIRBIUS_H */
