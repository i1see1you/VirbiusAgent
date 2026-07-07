//! Falco plugin mode configuration generator.
//!
//! When eBPF is unavailable (serverless, unprivileged containers), Falco
//! degrades to plugin mode — consuming log/audit events instead of syscalls.
//!
//! Supported plugins (§4.5):
//! - k8saudit: Kubernetes API server audit logs
//! - filetail: Tail log files (e.g., Agent stdout/stderr)
//! - virbius-audit: Custom plugin consuming Redis Stream audit events
//! - cloudtrail: AWS CloudTrail events
//!
//! This module generates the Falco configuration YAML and deployment manifests
//! appropriate for the detected kernel mode.

use crate::detect::{KernelInfo, KernelMode};
use serde::Serialize;

/// Falco plugin definition.
#[derive(Debug, Clone, Serialize)]
pub struct FalcoPluginConfig {
    /// Plugin name (e.g., "k8saudit", "filetail")
    pub name: String,
    /// Library path (e.g., "libk8saudit.so")
    pub library_path: String,
    /// Init configuration (JSON string, plugin-specific)
    pub init_config: String,
    /// Open parameters (JSON string, plugin-specific)
    pub open_params: String,
}

/// Falco configuration for a specific deployment mode.
#[derive(Debug, Clone, Serialize)]
pub struct FalcoModeConfig {
    /// The kernel mode this config is for
    pub mode: String,
    /// Whether eBPF driver is used
    pub uses_ebpf: bool,
    /// Whether this is a plugin-only deployment
    pub plugin_only: bool,
    /// List of enabled plugins
    pub plugins: Vec<FalcoPluginConfig>,
    /// Falco rules content (YAML)
    pub rules_content: String,
    /// Deployment manifest (K8s YAML)
    pub deployment_manifest: String,
}

/// Generate the appropriate Falco configuration based on the detected kernel mode.
pub fn generate_config(info: &KernelInfo) -> FalcoModeConfig {
    match info.mode {
        KernelMode::FalcoPlugin => generate_plugin_mode_config(info),
        KernelMode::FalcoUserspace => generate_userspace_config(info),
        KernelMode::FalcoEbpf => generate_ebpf_config(info),
        KernelMode::Tetragon => generate_ebpf_config(info), // Tetragon uses same Falco config as eBPF
        KernelMode::Disabled => generate_disabled_config(),
    }
}

/// Plugin mode: no eBPF, uses k8saudit + filetail + virbius-audit.
fn generate_plugin_mode_config(_info: &KernelInfo) -> FalcoModeConfig {
    let plugins = vec![
        FalcoPluginConfig {
            name: "k8saudit".to_string(),
            library_path: "libk8saudit.so".to_string(),
            init_config: serde_json::json!({
                "bufferSize": 100,
                "sslCertificate": "/etc/falco/k8saudit.crt"
            }).to_string(),
            open_params: serde_json::json!({
                "url": "https://kubernetes.default.svc:443/audit-events"
            }).to_string(),
        },
        FalcoPluginConfig {
            name: "filetail".to_string(),
            library_path: "libfiletail.so".to_string(),
            init_config: serde_json::json!({}).to_string(),
            open_params: serde_json::json!({
                "filename": "/var/log/virbius/agent.log"
            }).to_string(),
        },
        FalcoPluginConfig {
            name: "virbius-audit".to_string(),
            library_path: "libvirbius_audit.so".to_string(),
            init_config: serde_json::json!({
                "redisUrl": "redis://virbius-redis:6379",
                "streamKey": "virbius:audit",
                "consumerGroup": "falco-virbius"
            }).to_string(),
            open_params: serde_json::json!({}).to_string(),
        },
    ];

    FalcoModeConfig {
        mode: KernelMode::FalcoPlugin.as_str().to_string(),
        uses_ebpf: false,
        plugin_only: true,
        plugins,
        rules_content: plugin_rules_yaml(),
        deployment_manifest: plugin_daemonset_yaml(),
    }
}

/// Userspace mode: ptrace driver, no eBPF, no plugins needed.
fn generate_userspace_config(_info: &KernelInfo) -> FalcoModeConfig {
    FalcoModeConfig {
        mode: KernelMode::FalcoUserspace.as_str().to_string(),
        uses_ebpf: false,
        plugin_only: false,
        plugins: vec![],
        rules_content: ebpf_rules_yaml(),
        deployment_manifest: userspace_daemonset_yaml(),
    }
}

/// eBPF mode: full syscall visibility.
fn generate_ebpf_config(_info: &KernelInfo) -> FalcoModeConfig {
    FalcoModeConfig {
        mode: KernelMode::FalcoEbpf.as_str().to_string(),
        uses_ebpf: true,
        plugin_only: false,
        plugins: vec![],
        rules_content: ebpf_rules_yaml(),
        deployment_manifest: ebpf_daemonset_yaml(),
    }
}

/// Disabled: no kernel observation.
fn generate_disabled_config() -> FalcoModeConfig {
    FalcoModeConfig {
        mode: KernelMode::Disabled.as_str().to_string(),
        uses_ebpf: false,
        plugin_only: false,
        plugins: vec![],
        rules_content: "".to_string(),
        deployment_manifest: "".to_string(),
    }
}

/// Falco rules for plugin mode.
/// Focuses on infrastructure events (no syscall visibility).
fn plugin_rules_yaml() -> String {
    r#"- rule: Agent pod unauthorized access
  desc: Detect unauthorized access to Agent pods
  condition: ka.req.pod.host_network and not ka.user.name in (falco, virbius)
  output: >
    Unauthorized pod host network access (user=%ka.user.name,
    pod=%ka.req.pod.name, namespace=%ka.req.pod.namespace)
  priority: WARNING
  tags: [agent, k8s, network]

- rule: Agent configmap modified
  desc: Detect modifications to Agent configuration
  condition: ka.target.configmap and ka.req.configmap.name startswith "virbius"
  output: >
    Agent ConfigMap modified (user=%ka.user.name,
    configmap=%ka.req.configmap.name, namespace=%ka.req.configmap.namespace)
  priority: NOTICE
  tags: [agent, k8s, config]

- rule: Agent log anomaly
  desc: Detect anomalies in Agent log files via filetail
  condition: evt.type = read and fd.name = "/var/log/virbius/agent.log"
    and evt.buffer contains "ERROR"
  output: >
    Agent log error (file=%fd.name, line=%evt.buffer)
  priority: WARNING
  tags: [agent, log]

- rule: Session risk accumulation
  desc: Detect rapid session risk score increase from virbius-audit plugin
  condition: virbius.session.risk_score > 80
  output: >
    High session risk detected (session=%virbius.session.id,
    risk=%virbius.session.risk_score, agent=%virbius.session.app_id)
  priority: CRITICAL
  tags: [agent, risk, session]
"#.to_string()
}

/// Falco rules for eBPF/userspace mode.
/// Includes syscall-level rules.
fn ebpf_rules_yaml() -> String {
    r#"- rule: Agent process spawned
  desc: Detect new processes in Agent context
  condition: >
    spawned_process
    and not proc.name startswith "falco"
    and not proc.name startswith "virbius"
  output: >
    Agent process spawned (user=%user.name, command=%proc.cmdline, pid=%proc.pid)
  priority: WARNING
  tags: [agent, process]

- rule: Agent network connection
  desc: Detect outbound connections from Agent
  condition: >
    outbound
    and not fd.sip in (trusted_ips)
  output: >
    Agent outbound connection (fd=%fd.name, pid=%proc.pid)
  priority: NOTICE
  tags: [agent, network]

- rule: Agent file access
  desc: Detect suspicious file access by Agent processes
  condition: >
    open_write
    and fd.name startswith /etc/
    and not proc.name in (known_procs)
  output: >
    Agent file write to /etc (file=%fd.name, proc=%proc.name, pid=%proc.pid)
  priority: WARNING
  tags: [agent, file]

- rule: Agent shell spawned
  desc: Detect shell execution by Agent
  condition: >
    spawned_process
    and proc.name in (bash, sh, zsh, dash)
    and container.id != host
  output: >
    Shell spawned in Agent container (proc=%proc.name, pid=%proc.pid,
    container=%container.id)
  priority: CRITICAL
  tags: [agent, shell, container]
"#.to_string()
}

/// K8s DaemonSet for plugin mode (serverless/pod-observe).
/// Non-privileged: no host PID/network access, no /dev access.
fn plugin_daemonset_yaml() -> String {
    r#"apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: falco-plugin
  namespace: virbius-system
  labels:
    app: falco
    mode: plugin
spec:
  selector:
    matchLabels:
      app: falco
      mode: plugin
  template:
    metadata:
      labels:
        app: falco
        mode: plugin
    spec:
      serviceAccountName: falco-k8saudit
      containers:
        - name: falco
          image: falcosecurity/falco:0.39.0
          securityContext:
            runAsNonRoot: true
            runAsUser: 1000
            allowPrivilegeEscalation: false
          env:
            - name: FALCO_DRIVER
              value: plugin
          volumeMounts:
            - name: falco-config
              mountPath: /etc/falco/falco.yaml
              subPath: falco.yaml
            - name: falco-rules
              mountPath: /etc/falco/falco_rules.local.yaml
              subPath: falco_rules.local.yaml
            - name: agent-logs
              mountPath: /var/log/virbius
              readOnly: true
            - name: k8s-audit-cert
              mountPath: /etc/falco/k8saudit.crt
              readOnly: true
      volumes:
        - name: falco-config
          configMap:
            name: falco-plugin-config
        - name: falco-rules
          configMap:
            name: falco-plugin-rules
        - name: agent-logs
          hostPath:
            path: /var/log/virbius
            type: DirectoryOrCreate
        - name: k8s-audit-cert
          secret:
            secretName: k8s-audit-cert
"#.to_string()
}

/// K8s DaemonSet for userspace mode (ptrace driver).
/// Requires CAP_SYS_PTRACE but not full root.
fn userspace_daemonset_yaml() -> String {
    r#"apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: falco-userspace
  namespace: virbius-system
  labels:
    app: falco
    mode: userspace
spec:
  selector:
    matchLabels:
      app: falco
      mode: userspace
  template:
    metadata:
      labels:
        app: falco
        mode: userspace
    spec:
      hostPID: true
      containers:
        - name: falco
          image: falcosecurity/falco:0.39.0
          securityContext:
            capabilities:
              add: [SYS_PTRACE]
          env:
            - name: FALCO_DRIVER
              value: userspace
          volumeMounts:
            - name: falco-config
              mountPath: /etc/falco/falco.yaml
              subPath: falco.yaml
            - name: falco-rules
              mountPath: /etc/falco/falco_rules.local.yaml
              subPath: falco_rules.local.yaml
            - name: proc-fs
              mountPath: /host/proc
              readOnly: true
      volumes:
        - name: falco-config
          configMap:
            name: falco-config
        - name: falco-rules
          configMap:
            name: falco-rules
        - name: proc-fs
          hostPath:
            path: /proc
"#.to_string()
}

/// K8s DaemonSet for eBPF mode (full privileged).
fn ebpf_daemonset_yaml() -> String {
    r#"apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: falco-ebpf
  namespace: virbius-system
  labels:
    app: falco
    mode: ebpf
spec:
  selector:
    matchLabels:
      app: falco
      mode: ebpf
  template:
    metadata:
      labels:
        app: falco
        mode: ebpf
    spec:
      hostNetwork: true
      containers:
        - name: falco
          image: falcosecurity/falco:0.39.0
          securityContext:
            privileged: true
          env:
            - name: FALCO_DRIVER
              value: ebpf
          volumeMounts:
            - name: falco-config
              mountPath: /etc/falco/falco.yaml
              subPath: falco.yaml
            - name: falco-rules
              mountPath: /etc/falco/falco_rules.local.yaml
              subPath: falco_rules.local.yaml
            - name: dev-fs
              mountPath: /dev
            - name: proc-fs
              mountPath: /host/proc
              readOnly: true
            - name: boot-fs
              mountPath: /host/boot
              readOnly: true
      volumes:
        - name: falco-config
          configMap:
            name: falco-config
        - name: falco-rules
          configMap:
            name: falco-rules
        - name: dev-fs
          hostPath:
            path: /dev
        - name: proc-fs
          hostPath:
            path: /proc
        - name: boot-fs
          hostPath:
            path: /boot
"#.to_string()
}

/// Generate the falco.yaml main config for plugin mode.
pub fn plugin_falco_yaml() -> String {
    r#"json_output: true
json_include_output_property: true

# Plugin mode: no syscall driver
driver:
  kind: plugin

# Plugins loaded at startup
plugins:
  - name: k8saudit
    library_path: libk8saudit.so
    init_config:
      bufferSize: 100
      sslCertificate: /etc/falco/k8saudit.crt
    open_params: "https://kubernetes.default.svc:443/audit-events"
  - name: filetail
    library_path: libfiletail.so
    init_config:
    open_params: "/var/log/virbius/agent.log"
  - name: virbius-audit
    library_path: libvirbius_audit.so
    init_config:
      redisUrl: "redis://virbius-redis:6379"
      streamKey: "virbius:audit"
      consumerGroup: "falco-virbius"
    open_params:

# Watch k8saudit + filetail + virbius-audit event sources
watchers:
  - name: k8s_audit
    plugin_name: k8saudit
  - name: file_tail
    plugin_name: filetail
  - name: virbius_audit
    plugin_name: virbius-audit

stdout_output:
  enabled: false
file_output:
  enabled: false
grpc:
  enabled: false
webserver:
  enabled: true
  listen_port: 8765
  k8s_healthz_endpoint: /healthz
program_output:
  enabled: true
  keep_alive: false
  program: |
    virbius-kernel-falco-ingest
"#.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_plugin_mode_config_has_plugins() {
        let info = KernelInfo {
            mode: KernelMode::FalcoPlugin,
            kernel_version: (5, 4, 0),
            btf_available: false,
            has_cap_bpf: false,
            has_cap_sys_admin: false,
            has_cap_sys_ptrace: false,
            has_kprobe_override: false,
            is_root: false,
        };
        let config = generate_config(&info);
        assert!(config.plugin_only);
        assert!(!config.uses_ebpf);
        assert_eq!(config.plugins.len(), 3);
        assert!(config.plugins.iter().any(|p| p.name == "k8saudit"));
        assert!(config.plugins.iter().any(|p| p.name == "filetail"));
        assert!(config.plugins.iter().any(|p| p.name == "virbius-audit"));
    }

    #[test]
    fn test_ebpf_mode_no_plugins() {
        let info = KernelInfo {
            mode: KernelMode::FalcoEbpf,
            kernel_version: (5, 8, 0),
            btf_available: true,
            has_cap_bpf: true,
            has_cap_sys_admin: true,
            has_cap_sys_ptrace: true,
            has_kprobe_override: true,
            is_root: true,
        };
        let config = generate_config(&info);
        assert!(config.uses_ebpf);
        assert!(!config.plugin_only);
        assert!(config.plugins.is_empty());
    }

    #[test]
    fn test_disabled_mode_empty() {
        let info = KernelInfo {
            mode: KernelMode::Disabled,
            kernel_version: (0, 0, 0),
            btf_available: false,
            has_cap_bpf: false,
            has_cap_sys_admin: false,
            has_cap_sys_ptrace: false,
            has_kprobe_override: false,
            is_root: false,
        };
        let config = generate_config(&info);
        assert!(config.plugins.is_empty());
        assert!(config.rules_content.is_empty());
        assert!(config.deployment_manifest.is_empty());
    }

    #[test]
    fn test_plugin_rules_contain_k8s_audit() {
        let rules = plugin_rules_yaml();
        assert!(rules.contains("Agent pod unauthorized access"));
        assert!(rules.contains("Agent configmap modified"));
        assert!(rules.contains("Session risk accumulation"));
    }

    #[test]
    fn test_plugin_daemonset_non_privileged() {
        let yaml = plugin_daemonset_yaml();
        assert!(yaml.contains("runAsNonRoot: true"));
        assert!(yaml.contains("allowPrivilegeEscalation: false"));
        assert!(!yaml.contains("privileged: true"));
    }

    #[test]
    fn test_ebpf_daemonset_privileged() {
        let yaml = ebpf_daemonset_yaml();
        assert!(yaml.contains("privileged: true"));
    }

    #[test]
    fn test_plugin_falco_yaml_has_driver_plugin() {
        let yaml = plugin_falco_yaml();
        assert!(yaml.contains("kind: plugin"));
        assert!(yaml.contains("k8saudit"));
        assert!(yaml.contains("virbius-audit"));
    }
}
