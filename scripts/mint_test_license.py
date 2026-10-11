"""Mint static Ed25519 License fixtures for virbius-mcp-python tests.

Dependency-free: shells out to `openssl` (>=1.1.1, Ed25519 support).
Writes/refreshes `tests/fixtures/license.jwt`, `license_expired.jwt`, `license_pub.pem`.

The JWT layout matches `virbius-core`'s `License::verify`:
  header (EdDSA) "." payload (LicenseClaims JSON, base64url-no-pad) "." signature

Usage:
    python3 scripts/mint_test_license.py
"""

import base64
import json
import pathlib
import subprocess
import tempfile

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES = REPO_ROOT / "virbius-mcp-python" / "tests" / "fixtures"

# {"alg":"EdDSA","typ":"JWT"}
HEADER_B64 = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9"

APP_ID = "test-agent"

VALID_CLAIMS = {
    "app_id": APP_ID,
    "tenant_id": "tenant-1",
    "agent_name": "Test Agent",
    "allowed_tools": ["read_file", "search"],
    "risk_quota": 100,
    "tool_rate_limit": 50,
    "exp": 9999999999,
    "iat": 1700000000,
}

EXPIRED_CLAIMS = {**VALID_CLAIMS, "exp": 1700000000}  # 2023 — already expired


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mint(private_key: pathlib.Path, claims: dict) -> str:
    payload = b64url(json.dumps(claims, separators=(",", ":")).encode())
    signing_input = f"{HEADER_B64}.{payload}"
    with tempfile.TemporaryDirectory() as td:
        msg = pathlib.Path(td) / "msg.txt"
        sig = pathlib.Path(td) / "sig.bin"
        msg.write_bytes(signing_input.encode())
        subprocess.run(
            ["openssl", "pkeyutl", "-sign", "-inkey", str(private_key), "-rawin",
             "-in", str(msg), "-out", str(sig)],
            check=True, capture_output=True,
        )
        signature = b64url(sig.read_bytes())
    return f"{HEADER_B64}.{payload}.{signature}"


def main() -> None:
    FIXTURES.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as td:
        td = pathlib.Path(td)
        private_key = td / "priv.pem"
        public_key = td / "pub.pem"
        subprocess.run(
            ["openssl", "genpkey", "-algorithm", "ed25519", "-out", str(private_key)],
            check=True, capture_output=True,
        )
        subprocess.run(
            ["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
            check=True, capture_output=True,
        )
        (FIXTURES / "license.jwt").write_text(mint(private_key, VALID_CLAIMS) + "\n")
        (FIXTURES / "license_expired.jwt").write_text(mint(private_key, EXPIRED_CLAIMS) + "\n")
        (FIXTURES / "license_pub.pem").write_text(public_key.read_text())

    print("wrote:", *(p.name for p in sorted(FIXTURES.glob("license*"))))


if __name__ == "__main__":
    main()
