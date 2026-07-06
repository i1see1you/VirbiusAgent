#[cfg(target_os = "linux")]
pub mod landlock;

#[derive(Debug)]
pub struct SandboxResult {
    pub stdout: String,
    pub stderr: String,
    pub exit_code: i32,
}
