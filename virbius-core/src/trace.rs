#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TraceIdSource {
    Client,
    SdkGenerated,
}

impl TraceIdSource {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Client => "client",
            Self::SdkGenerated => "sdk",
        }
    }
}

pub fn generate_trace_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

/// Canonical hyphenated UUID v4 (36 chars, version=4, RFC 4122 variant).
pub fn valid_trace_id(id: &str) -> bool {
    uuid::Uuid::parse_str(id)
        .ok()
        .is_some_and(|u| u.get_version_num() == 4)
}

/// Auto-generate when missing; validate UUID v4 when provided.
pub fn resolve_trace_id(provided: Option<&str>) -> Result<(String, TraceIdSource), TraceIdError> {
    match provided.map(str::trim).filter(|s| !s.is_empty()) {
        None => Ok((generate_trace_id(), TraceIdSource::SdkGenerated)),
        Some(id) if valid_trace_id(id) => Ok((id.to_string(), TraceIdSource::Client)),
        Some(_) => Err(TraceIdError),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TraceIdError;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_uuid_v4() {
        let id = generate_trace_id();
        assert!(valid_trace_id(&id));
        assert_eq!(id.len(), 36);
    }

    #[test]
    fn accepts_canonical_v4() {
        assert!(valid_trace_id("550e8400-e29b-41d4-a716-446655440000"));
    }

    #[test]
    fn rejects_invalid_trace_id() {
        assert!(resolve_trace_id(Some("bad")).is_err());
        assert!(!valid_trace_id("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        assert!(!valid_trace_id("6ba7b810-9dad-11d1-80b4-00c04fd413c1"));
        assert!(!valid_trace_id("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }
}
