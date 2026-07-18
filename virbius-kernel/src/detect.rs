use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KernelMode {
    FalcoEbpf,
    FalcoUserspace,
    Disabled,
}

impl KernelMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            KernelMode::FalcoEbpf => "falco-ebpf",
            KernelMode::FalcoUserspace => "falco-userspace",
            KernelMode::Disabled => "disabled",
        }
    }

    /// Kernel-level enforcement is handled by the edge layer (Landlock + gVisor).
    /// The kernel layer is observation-only.
    pub fn has_enforcement(&self) -> bool {
        false
    }

    pub fn has_observation(&self) -> bool {
        !matches!(self, KernelMode::Disabled)
    }

    pub fn uses_ebpf(&self) -> bool {
        matches!(self, KernelMode::FalcoEbpf)
    }
}

#[derive(Debug, Clone)]
pub struct KernelInfo {
    pub mode: KernelMode,
    pub kernel_version: (u32, u32, u32),
    pub btf_available: bool,
    pub has_cap_bpf: bool,
    pub has_cap_sys_admin: bool,
    pub has_cap_sys_ptrace: bool,
    pub is_root: bool,
}

pub fn detect() -> KernelMode {
    detect_full().mode
}

pub fn detect_full() -> KernelInfo {
    let is_root = unsafe { libc::geteuid() } == 0;
    let kver = kernel_version();
    let btf_ok = btf_available();
    let cap_eff = read_cap_effective().unwrap_or(0u64);

    let has_cap_sys_admin = cap_eff & (1u64 << 21) != 0;
    let has_cap_bpf = cap_eff & (1u64 << 39) != 0;
    let has_cap_perfmon = cap_eff & (1u64 << 38) != 0;
    let has_cap_sys_ptrace = cap_eff & (1u64 << 19) != 0;
    let has_caps = is_root || has_cap_sys_admin || (has_cap_bpf && has_cap_perfmon);

    let mode = if !has_caps && !has_cap_sys_ptrace {
        KernelMode::Disabled
    } else if kver < (5, 8, 0) || !btf_ok || !has_caps {
        if has_cap_sys_ptrace {
            KernelMode::FalcoUserspace
        } else {
            KernelMode::Disabled
        }
    } else {
        KernelMode::FalcoEbpf
    };

    KernelInfo {
        mode,
        kernel_version: kver,
        btf_available: btf_ok,
        has_cap_bpf,
        has_cap_sys_admin,
        has_cap_sys_ptrace,
        is_root,
    }
}

fn read_cap_effective() -> Option<u64> {
    let status = fs::read_to_string("/proc/self/status").ok()?;
    for line in status.lines() {
        if let Some(val) = line.strip_prefix("CapEff:\t") {
            return u64::from_str_radix(val.trim(), 16).ok();
        }
    }
    None
}

fn kernel_version() -> (u32, u32, u32) {
    if let Ok(osrelease) = fs::read_to_string("/proc/sys/kernel/osrelease") {
        return parse_version(&osrelease);
    }
    let release = std::env::var("VIRBIUS_KERNEL_RELEASE").unwrap_or_default();
    if !release.is_empty() {
        return parse_version(&release);
    }
    if let Ok(version) = fs::read_to_string("/proc/version") {
        for part in version.split_whitespace() {
            if part.chars().next().map_or(false, |c| c.is_ascii_digit()) {
                return parse_version(part);
            }
        }
    }
    (0, 0, 0)
}

fn parse_version(s: &str) -> (u32, u32, u32) {
    let parts: Vec<&str> = s.trim().split('.').collect();
    let major = parts.first().and_then(|p| p.parse().ok()).unwrap_or(0);
    let minor = parts.get(1).and_then(|p| {
        let clean = p.split(|c: char| !c.is_ascii_digit()).next().unwrap_or(p);
        clean.parse().ok()
    }).unwrap_or(0);
    let patch = parts.get(2).and_then(|p| {
        let clean = p.split(|c: char| !c.is_ascii_digit()).next().unwrap_or(p);
        clean.parse().ok()
    }).unwrap_or(0);
    (major, minor, patch)
}

fn btf_available() -> bool {
    let path = Path::new("/sys/kernel/btf/vmlinux");
    path.exists() && path.metadata().map(|m| m.len() > 0).unwrap_or(false)
}

pub fn format_info(info: &KernelInfo) -> String {
    serde_json::json!({
        "mode": info.mode.as_str(),
        "kernel_version": format!("{}.{}.{}", info.kernel_version.0, info.kernel_version.1, info.kernel_version.2),
        "btf_available": info.btf_available,
        "has_cap_bpf": info.has_cap_bpf,
        "has_cap_sys_admin": info.has_cap_sys_admin,
        "has_cap_sys_ptrace": info.has_cap_sys_ptrace,
        "is_root": info.is_root,
    }).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_version() {
        assert_eq!(parse_version("5.10.0"), (5, 10, 0));
        assert_eq!(parse_version("6.7.4-arch1"), (6, 7, 4));
        assert_eq!(parse_version("5.15.0-102-generic"), (5, 15, 0));
        assert_eq!(parse_version(""), (0, 0, 0));
    }

    #[test]
    fn test_kernel_mode_str() {
        assert_eq!(KernelMode::FalcoEbpf.as_str(), "falco-ebpf");
        assert_eq!(KernelMode::Disabled.as_str(), "disabled");
    }

    #[test]
    fn test_has_observation() {
        assert!(KernelMode::FalcoEbpf.has_observation());
        assert!(!KernelMode::Disabled.has_observation());
    }

    #[test]
    fn test_uses_ebpf() {
        assert!(KernelMode::FalcoEbpf.uses_ebpf());
        assert!(!KernelMode::FalcoUserspace.uses_ebpf());
        assert!(!KernelMode::Disabled.uses_ebpf());
    }
}
