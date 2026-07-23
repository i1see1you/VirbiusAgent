//! Integration tests for the Landlock + self-pipe sandbox.
//!
//! These tests validate:
//! 1. Self-pipe `landlock_applied` reporting accuracy
//! 2. Parent-side timeout kills long-running child
//! 3. `execute_sandboxed` convenience function
//!
//! All tests require Linux (the sandbox module is gated on `#[cfg(target_os = "linux")]`)
//! and skip gracefully when Landlock is not available on the running kernel.
//!
//! Run with: `cargo test --test sandbox_integration -- --nocapture`

#![cfg(target_os = "linux")]

use std::time::Duration;

use virbius_core::sandbox::{detect_abi_version, execute_sandboxed, LandlockAbi, LandlockRules};

/// Returns true if the running kernel supports Landlock (ABI ≥ V1).
fn landlock_available() -> bool {
    detect_abi_version() != LandlockAbi::None
}

// ─── Self-pipe reporting ──────────────────────────────────────────────

#[test]
fn test_self_pipe_reports_landlock_applied() {
    if !landlock_available() {
        eprintln!("SKIP: Landlock not available on this kernel");
        return;
    }

    let rules = LandlockRules {
        read_paths: vec![
            "/etc/hostname".into(),
            "/lib/**".into(),
            "/lib64/**".into(),
            "/usr/lib/**".into(),
        ],
        exec_paths: vec!["/usr/bin/cat".into()],
        ..Default::default()
    };

    let result = execute_sandboxed(
        "/usr/bin/cat",
        &["/etc/hostname".to_string()],
        rules,
        Duration::from_secs(5),
    )
    .expect("sandbox execution should succeed");

    assert_eq!(result.exit_code, 0, "cat /etc/hostname should exit 0");
    assert!(!result.stdout.is_empty(), "should read hostname content");
    assert!(
        result.landlock_applied,
        "self-pipe should report landlock_applied=true"
    );
    assert!(
        result.caps_dropped,
        "self-pipe should report caps_dropped=true"
    );
}

// ─── Timeout ──────────────────────────────────────────────────────────

#[test]
fn test_parent_timeout_kills_child() {
    let rules = if landlock_available() {
        LandlockRules {
            read_paths: vec!["/**".into()],
            exec_paths: vec!["/usr/bin/sleep".into(), "/bin/sleep".into()],
            ..Default::default()
        }
    } else {
        LandlockRules::default()
    };

    let result = execute_sandboxed(
        "/usr/bin/sleep",
        &["10".to_string()],
        rules,
        Duration::from_millis(100),
    );

    let err = result.expect_err("sandbox should time out");
    assert!(
        err.contains("timeout"),
        "error should mention timeout, got: {err}"
    );
}

// ─── execute_sandboxed convenience ────────────────────────────────────

#[test]
fn test_execute_sandboxed_invalid_program() {
    let result = execute_sandboxed(
        "/usr/bin/nonexistent_xyzzy",
        &[],
        LandlockRules::default(),
        Duration::from_secs(5),
    );

    assert!(result.is_err(), "non-existent program should return Err");
}
