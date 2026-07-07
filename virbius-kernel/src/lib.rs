pub mod detect;
pub mod falco_plugin;
pub mod pidmap;

pub use detect::{detect, detect_full, format_info, KernelInfo, KernelMode};
pub use falco_plugin::{generate_config, FalcoModeConfig, FalcoPluginConfig, plugin_falco_yaml};
pub use pidmap::{lookup_agent, lookup_by_cgroup, register_agent, unregister_agent, PidMapEntry};
