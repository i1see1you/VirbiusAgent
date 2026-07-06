/// Kernel mode detection: chooses between Tetragon, Falco eBPF, Falco userspace,
/// Falco plugin, or disabled based on available capabilities and kernel features.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KernelMode {
    /// Full eBPF + enforcement (P2)
    Tetragon,
    /// eBPF observation only, no enforcement
    FalcoEbpf,
    /// ptrace-based driver
    FalcoUserspace,
    /// Pure log/audit plugin mode
    FalcoPlugin,
    /// Observation disabled
    Disabled,
}

/// Detect kernel mode based on capabilities and kernel version.
pub fn detect() -> KernelMode {
    // TODO: P2 implementation - probe /sys/kernel/btf/vmlinux, check caps, etc.
    if cfg!(target_os = "linux") {
        KernelMode::FalcoEbpf
    } else {
        KernelMode::FalcoPlugin
    }
}
