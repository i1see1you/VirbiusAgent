//! Integration tests for the gVisor (runsc) sandbox.
//!
//! Requirements:
//! - Linux (gVisor is Linux-only)
//! - `runsc` binary (default: /usr/local/bin/runsc, override via RUNSC_PATH env)
//! - A rootfs directory (set VIRBIUS_ROOTFS env var, or Docker will be used to prepare one)
//!
//! Run with:
//!   VIRBIUS_ROOTFS=/tmp/virbius-gvisor-rootfs cargo test --test gvisor_integration -- --nocapture --test-threads=1

#![cfg(target_os = "linux")]

use std::path::Path;
use std::time::Duration;

use virbius_core::sandbox::{GvisorPool, GvisorPoolConfig, Language};

// ─── Helpers ──────────────────────────────────────────────────────────

fn runsc_path() -> String {
    std::env::var("RUNSC_PATH").unwrap_or_else(|_| "/usr/local/bin/runsc".to_string())
}

fn runsc_available() -> bool {
    Path::new(&runsc_path()).exists()
}

fn ensure_rootfs() -> Result<String, String> {
    if let Ok(path) = std::env::var("VIRBIUS_ROOTFS") {
        // Check for either bin/sh or bin/busybox (Alpine uses busybox symlink)
        let sh_path = Path::new(&path).join("bin/sh");
        let busybox_path = Path::new(&path).join("bin/busybox");
        if sh_path.exists() || busybox_path.exists() {
            return Ok(path);
        }
        return Err(format!(
            "VIRBIUS_ROOTFS={path} does not contain bin/sh or bin/busybox"
        ));
    }

    Err("VIRBIUS_ROOTFS not set. Export it: VIRBIUS_ROOTFS=/tmp/virbius-gvisor-rootfs".into())
}

fn test_config(rootfs: &str) -> GvisorPoolConfig {
    GvisorPoolConfig {
        runsc_path: runsc_path(),
        bundle_root: "/tmp/virbius-gvisor-test-bundles".to_string(),
        rootfs_path: rootfs.to_string(),
        min_warm: 0,
        max_idle: 1,
        acquire_timeout: Duration::from_secs(15),
        exec_timeout: Duration::from_secs(10),
        memory_limit_bytes: 128 * 1024 * 1024,
        cpu_quota: 1.0,
        network_disabled: true,
    }
}

fn setup() -> Option<GvisorPool> {
    if !runsc_available() {
        eprintln!("SKIP: runsc not found at {}", runsc_path());
        return None;
    }

    let rootfs = match ensure_rootfs() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("SKIP: {e}");
            return None;
        }
    };

    let config = test_config(&rootfs);
    Some(GvisorPool::new(config))
}

// ─── Basic shell command execution ────────────────────────────────────

#[test]
fn test_gvisor_shell_echo() {
    let pool = match setup() {
        Some(v) => v,
        None => return,
    };

    let result = pool
        .execute(Language::Shell, "echo 'hello from gVisor'\n")
        .expect("execute should succeed");

    assert!(
        result.stdout.contains("hello from gVisor"),
        "stdout should contain the echoed message, got: {:?}",
        result.stdout
    );
    assert_eq!(result.exit_code, 0, "exit code should be 0");
}

// ─── Execution timeout ──────────────────────────────────────────────────

#[test]
fn test_gvisor_timeout() {
    let _ = match setup() {
        Some(v) => v,
        None => return,
    };

    let mut config = test_config(&ensure_rootfs().unwrap());
    config.exec_timeout = Duration::from_millis(200);

    let pool = GvisorPool::new(config);

    let err = pool
        .execute(Language::Shell, "sleep 30\n")
        .expect_err("should time out");

    assert!(
        err.contains("timeout"),
        "error should mention timeout, got: {err}"
    );
}

// ─── Pool availability detection ────────────────────────────────────────

#[test]
fn test_gvisor_pool_available() {
    if !runsc_available() {
        eprintln!("SKIP: runsc not found");
        return;
    }

    let config = GvisorPoolConfig::default();
    let pool = GvisorPool::new(config);
    assert!(
        pool.is_available(),
        "pool should report available when runsc exists"
    );
}

#[test]
fn test_gvisor_pool_unavailable_when_runsc_missing() {
    let config = GvisorPoolConfig {
        runsc_path: "/nonexistent/runsc".to_string(),
        ..Default::default()
    };
    let pool = GvisorPool::new(config);
    assert!(!pool.is_available(), "pool should report unavailable");
}

// ─── OCI config security ────────────────────────────────────────────────

#[test]
fn test_gvisor_oci_config_no_capabilities() {
    let pool = match setup() {
        Some(v) => v,
        None => return,
    };

    let config = pool.build_oci_config(Language::Shell);
    let parsed: serde_json::Value = serde_json::from_str(&config).unwrap();

    // All capability sets should be empty
    let caps = &parsed["process"]["capabilities"];
    for key in [
        "bounding",
        "effective",
        "inheritable",
        "permitted",
        "ambient",
    ] {
        assert_eq!(
            caps[key].as_array().unwrap().len(),
            0,
            "capability {key} should be empty"
        );
    }

    // noNewPrivileges should be true
    assert_eq!(
        parsed["process"]["noNewPrivileges"].as_bool(),
        Some(true),
        "noNewPrivileges should be true"
    );

    // Memory limit should be set
    assert!(
        parsed["linux"]["resources"]["memory"]["limit"]
            .as_u64()
            .unwrap()
            > 0,
        "memory limit should be set"
    );
}
