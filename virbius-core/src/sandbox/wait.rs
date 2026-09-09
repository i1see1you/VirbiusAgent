//! Bounded process waits for sandbox teardown.
//!
//! `Child::wait` and `JoinHandle::join` can block forever (stuck `runsc`,
//! pipes that never EOF). Callers must use these helpers so MCP sessions
//! can still return a JSON-RPC error.

use std::io::Read;
use std::process::{Child, Command, ExitStatus, Output, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

/// Per-step cap for kill / try_wait / `runsc delete`.
pub const REAP_STEP: Duration = Duration::from_millis(300);
/// Total budget for tearing down a stuck child (several REAP_STEPs).
pub const REAP_BUDGET: Duration = Duration::from_secs(1);
/// Added on top of acquire + exec for the MCP SSE wall (`reap` + grace).
pub const WALL_SLACK: Duration = Duration::from_secs(2);

/// MCP session wall: acquire (gVisor pool) + command deadline + slack.
pub fn mcp_wall(acquire: Duration, exec: Duration) -> Duration {
    acquire.saturating_add(exec).saturating_add(WALL_SLACK)
}

/// Poll `try_wait` until `deadline`. Never calls blocking [`Child::wait`].
pub fn wait_until(child: &mut Child, deadline: Instant) -> Option<ExitStatus> {
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status),
            Ok(None) => {
                if Instant::now() >= deadline {
                    return None;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            Err(_) => return None,
        }
    }
}

/// SIGKILL then wait up to [`REAP_STEP`]. Returns whether the child exited.
pub fn kill_and_wait_brief(child: &mut Child) -> Option<ExitStatus> {
    let _ = child.kill();
    wait_until(child, Instant::now() + REAP_STEP)
}

/// Best-effort reap on a background thread that also has a deadline.
///
/// If the child is still running after [`REAP_STEP`], the handle is forgotten
/// so this thread does not block in [`Child`]'s `Drop` (`wait()`).
pub fn detach_reap(mut child: Child) {
    std::thread::spawn(move || {
        let _ = child.kill();
        if wait_until(&mut child, Instant::now() + REAP_STEP).is_none() {
            std::mem::forget(child);
        }
    });
}

/// Kill, wait briefly, then [`detach_reap`] if the child is still running.
pub fn abort_child(mut child: Child) {
    if kill_and_wait_brief(&mut child).is_none() {
        detach_reap(child);
    }
}

/// Recv a buffer with a cap (stdout/stderr reader threads).
pub fn recv_buf_timeout(rx: mpsc::Receiver<Vec<u8>>, cap: Duration) -> Vec<u8> {
    rx.recv_timeout(cap).unwrap_or_default()
}

/// Run `cmd`, waiting at most `cap`. Kills the child if the cap is exceeded.
pub fn run_command_timed(mut cmd: Command, cap: Duration) -> std::io::Result<Output> {
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = cmd.spawn()?;
    match wait_until(&mut child, Instant::now() + cap) {
        Some(status) => {
            let mut stdout = Vec::new();
            let mut stderr = Vec::new();
            if let Some(mut s) = child.stdout.take() {
                s.read_to_end(&mut stdout).ok();
            }
            if let Some(mut s) = child.stderr.take() {
                s.read_to_end(&mut stderr).ok();
            }
            Ok(Output {
                status,
                stdout,
                stderr,
            })
        }
        None => {
            abort_child(child);
            Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                "command timed out",
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mcp_wall_adds_acquire_exec_and_slack() {
        assert_eq!(
            mcp_wall(Duration::from_secs(10), Duration::from_secs(30)),
            Duration::from_secs(42)
        );
        assert_eq!(
            mcp_wall(Duration::ZERO, Duration::from_secs(30)),
            Duration::from_secs(32)
        );
    }

    #[test]
    fn wait_until_returns_none_on_deadline() {
        let mut child = Command::new("sleep")
            .arg("30")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .expect("spawn sleep");
        let got = wait_until(&mut child, Instant::now() + Duration::from_millis(80));
        assert!(got.is_none(), "sleep should still be running");
        abort_child(child);
    }

    #[test]
    fn wait_until_reaps_exited_child() {
        let mut child = Command::new("true")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .or_else(|_| {
                Command::new("sh")
                    .args(["-c", "exit 0"])
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .spawn()
            })
            .expect("spawn true");
        let got = wait_until(&mut child, Instant::now() + Duration::from_secs(2));
        assert!(got.is_some());
        assert!(got.unwrap().success());
    }

    #[test]
    fn run_command_timed_times_out() {
        let mut cmd = Command::new("sleep");
        cmd.arg("30");
        let err = run_command_timed(cmd, Duration::from_millis(80)).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::TimedOut);
    }
}
