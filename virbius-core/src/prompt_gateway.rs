/// Prompt Gateway: injects constitutional rules, dynamic context, tool descriptions,
/// and PII desensitization before sending prompts to the LLM.
use serde::{Deserialize, Serialize};
use std::sync::{OnceLock, RwLock};

static CONSTITUTION_CACHE: OnceLock<RwLock<ConstitutionTemplates>> = OnceLock::new();

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConstitutionTemplate {
    pub version: String,
    pub scene: String,
    pub system_prefix: String,
    pub dynamic_suffix: String,
    pub prohibitions: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConstitutionTemplates {
    pub templates: Vec<ConstitutionTemplate>,
}

#[derive(Debug, Clone)]
pub struct EnhanceContext {
    pub app_id: String,
    pub session_id: String,
    pub scene: String,
    pub risk_score: u32,
    pub recent_tools: Vec<ToolCallSummary>,
    pub license_tools: Vec<String>,
    pub constitution_version: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ToolCallSummary {
    pub tool_name: String,
    pub args: String,
    pub result_summary: String,
}

pub struct PromptGateway;

impl PromptGateway {
    pub fn new() -> Self {
        Self
    }

    /// Enhance messages: constitution prefix + dynamic context suffix + PII desensitization.
    pub fn enhance(
        &self,
        messages: &mut Vec<String>,
        ctx: &EnhanceContext,
    ) -> Result<(), String> {
        let constitution = Self::load_constitution(&ctx.scene, &ctx.constitution_version);

        let prefix = if let Some(ref c) = constitution {
            let rules_text = c
                .prohibitions
                .iter()
                .enumerate()
                .map(|(i, p)| format!("{}. {}", i + 1, p))
                .collect::<Vec<_>>()
                .join("\n");
            let tool_rules = if ctx.license_tools.is_empty() {
                String::new()
            } else {
                format!(
                    "\n\n### 可用工具\n{}",
                    ctx.license_tools.join(", ")
                )
            };
            format!(
                "{}\n\n{}\n{}\n{}",
                c.system_prefix
                    .replace("{{version}}", &c.version)
                    .replace("{{scene}}", &ctx.scene)
                    .replace("{{prohibitions}}", &rules_text)
                    .replace("{{tool_rules}}", &tool_rules),
                tool_rules,
                if ctx.recent_tools.is_empty() {
                    String::new()
                } else {
                    "\n## 最近活动\n".to_string()
                },
                ctx.recent_tools
                    .iter()
                    .map(|t| {
                        format!(
                            "- {}: {} -> {}",
                            t.tool_name, t.args, t.result_summary
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n")
            )
        } else {
            String::new()
        };

        if !prefix.is_empty() {
            let mut injected = false;
            for msg in messages.iter_mut() {
                if msg.contains("\"role\":\"system\"") || msg.contains("\"role\": \"system\"") {
                    if let Some(content_start) = msg.find("\"content\":\"") {
                        let insert_pos = content_start + "\"content\":\"".len();
                        msg.insert_str(
                            insert_pos,
                            &prefix.replace('\n', "\\n").replace('"', "\\\""),
                        );
                        injected = true;
                        break;
                    }
                }
            }
            if !injected {
                let sys_msg = format!(
                    "{{\"role\":\"system\",\"content\":\"{}\"}}",
                    prefix.replace('\n', "\\n").replace('"', "\\\"")
                );
                messages.insert(0, sys_msg);
            }
        }

        let manifest = crate::manifest::load();
        for msg in messages.iter_mut() {
            if msg.contains("\"role\":\"user\"") || msg.contains("\"role\":\"assistant\"") {
                if let Some(content_start) = msg.find("\"content\":\"") {
                    let prefix_len = "\"content\":\"".len();
                    let rest = &msg[content_start + prefix_len..];
                    if let Some(end) = rest.find("\"}") {
                        let content = &rest[..end];
                        let desensitized = crate::dlp::desensitize_in(
                            content,
                            &ctx.session_id,
                            &manifest.dlp_rules,
                            std::time::Duration::from_secs(1800),
                            Some(&ctx.session_id),
                        );
                        let new_content = format!(
                            "{}content\":\"{}\"{}",
                            &msg[..content_start],
                            desensitized
                                .text
                                .replace('"', "\\\"")
                                .replace('\n', "\\n"),
                            &msg[content_start + prefix_len + end..]
                        );
                        *msg = new_content;
                    }
                }
            }
        }

        Ok(())
    }

    fn load_constitution(scene: &str, version: &str) -> Option<ConstitutionTemplate> {
        let cache = CONSTITUTION_CACHE.get_or_init(|| {
            let path = crate::sync::EdgeInitConfig::resolve()
                .cache_dir
                .join("constitution_templates.json");
            if let Ok(raw) = std::fs::read_to_string(&path) {
                if let Ok(templates) = serde_json::from_str::<ConstitutionTemplates>(&raw) {
                    return RwLock::new(templates);
                }
            }
            RwLock::new(ConstitutionTemplates { templates: vec![] })
        });
        let guard = cache.read().unwrap();
        guard
            .templates
            .iter()
            .find(|t| t.scene == scene && t.version == version)
            .cloned()
    }

    pub fn reload_constitutions() {
        let path = crate::sync::EdgeInitConfig::resolve()
            .cache_dir
            .join("constitution_templates.json");
        if let Ok(raw) = std::fs::read_to_string(&path) {
            if let Ok(templates) = serde_json::from_str::<ConstitutionTemplates>(&raw) {
                if let Some(cache) = CONSTITUTION_CACHE.get() {
                    *cache.write().unwrap() = templates;
                }
            }
        }
    }
}
