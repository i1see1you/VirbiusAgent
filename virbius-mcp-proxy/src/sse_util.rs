//! Minimal SSE (Server-Sent Events) framing parser shared by the legacy
//! HTTP+SSE upstream client and the Streamable HTTP client.
//!
//! Implements the EventSource wire format parts the MCP transports rely on:
//! - events are separated by a blank line
//! - multiple `data:` fields are concatenated with `\n` into one payload
//! - `event:` and `id:` fields
//! - `:` comment lines (keepalives)
//! - LF / CRLF / lone CR line endings, including split across chunks
//!
//! Not implemented: `retry:` handling (reconnect policy is owned by each
//! client).

/// One complete SSE event.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SseEvent {
    /// Event type; `"message"` when the stream omitted an `event:` field.
    pub event: String,
    /// Concatenated `data:` payload (lines joined with `\n`).
    pub data: String,
    /// Last `id:` field seen up to and including this event, if any.
    pub id: Option<String>,
}

/// Incremental SSE parser: feed raw HTTP chunks, receive completed events.
#[derive(Debug, Default)]
pub struct SseParser {
    /// Raw bytes that have not been split into complete lines yet.
    ///
    /// Kept as bytes (not `String`) because an HTTP chunk boundary can fall in
    /// the middle of a UTF-8 sequence. `\n`/`\r` are ASCII and can never appear
    /// inside a multi-byte sequence, so decoding only after line splitting is
    /// safe and lossless.
    buffer: Vec<u8>,
    /// Whether the optional leading UTF-8 BOM has been examined.
    bom_checked: bool,
    event_type: String,
    data_lines: Vec<String>,
    last_event_id: Option<String>,
    /// Previous chunk ended with `\r` (already treated as a line break); the
    /// `\n` of a CRLF pair split across chunks must be swallowed.
    pending_cr: bool,
}

impl SseParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed raw bytes from the HTTP stream; returns every event completed by
    /// this chunk.
    pub fn push(&mut self, chunk: &[u8]) -> Vec<SseEvent> {
        let mut out = Vec::new();
        self.buffer.extend_from_slice(chunk);

        if !self.bom_checked {
            // A BOM may itself be split across chunks; wait until it is
            // decidable before parsing any line.
            if self.buffer.len() < 3 {
                if !self.buffer.is_empty() && [0xEFu8, 0xBB].starts_with(&self.buffer[..]) {
                    return out;
                }
            } else if self.buffer.starts_with(&[0xEF, 0xBB, 0xBF]) {
                self.buffer.drain(..3);
            }
            self.bom_checked = true;
        }

        // A CR at the previous chunk's end was already dispatched as a line
        // break; swallow the LF half of a split CRLF pair. An empty chunk
        // carries no evidence either way, so keep waiting.
        if self.pending_cr && !self.buffer.is_empty() {
            if self.buffer.first() == Some(&b'\n') {
                self.buffer.remove(0);
            }
            self.pending_cr = false;
        }

        loop {
            let Some(pos) = self.buffer.iter().position(|&b| b == b'\n' || b == b'\r') else {
                break;
            };
            let is_cr = self.buffer[pos] == b'\r';
            let cr_at_end = is_cr && pos + 1 == self.buffer.len();

            let mut line: Vec<u8> = self.buffer.drain(..=pos).collect();
            line.pop(); // drop the terminator

            if cr_at_end {
                self.pending_cr = true;
            } else if is_cr && self.buffer.first() == Some(&b'\n') {
                self.buffer.remove(0); // CRLF
            }

            match std::str::from_utf8(&line) {
                Ok(line) => self.process_line(line, &mut out),
                Err(_) => {
                    let line = String::from_utf8_lossy(&line);
                    self.process_line(&line, &mut out);
                }
            }
        }
        out
    }

    fn process_line(&mut self, line: &str, out: &mut Vec<SseEvent>) {
        if line.is_empty() {
            self.dispatch(out);
            return;
        }
        if line.starts_with(':') {
            return;
        }

        let (field, value) = match line.split_once(':') {
            // Per the spec only a single leading space is stripped from
            // the value; the rest is payload.
            Some((f, v)) => (f, v.strip_prefix(' ').unwrap_or(v)),
            None => (line, ""),
        };

        match field {
            "event" => self.event_type = value.to_string(),
            "data" => self.data_lines.push(value.to_string()),
            "id" => {
                if !value.contains('\0') {
                    self.last_event_id = Some(value.to_string());
                }
            }
            // `retry:` and unknown fields are ignored.
            _ => {}
        }
    }

    fn dispatch(&mut self, out: &mut Vec<SseEvent>) {
        if self.data_lines.is_empty() {
            // Blank line with no data must not produce an event.
            self.event_type.clear();
            return;
        }
        let event = if self.event_type.is_empty() {
            "message".to_string()
        } else {
            std::mem::take(&mut self.event_type)
        };
        let data = std::mem::take(&mut self.data_lines).join("\n");
        out.push(SseEvent {
            event,
            data,
            id: self.last_event_id.clone(),
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_single_event() {
        let mut p = SseParser::new();
        let events = p.push(b"event: endpoint\ndata: /messages/?session_id=abc\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event, "endpoint");
        assert_eq!(events[0].data, "/messages/?session_id=abc");
    }

    #[test]
    fn defaults_to_message_event_type() {
        let mut p = SseParser::new();
        let events = p.push(b"data: {\"id\":1}\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event, "message");
        assert_eq!(events[0].data, "{\"id\":1}");
    }

    #[test]
    fn concatenates_multiline_data() {
        let mut p = SseParser::new();
        let events = p.push(b"event: message\ndata: {\"id\":1,\ndata: \"result\":{}}\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "{\"id\":1,\n\"result\":{}}");
    }

    #[test]
    fn ignores_comments_and_keepalives() {
        let mut p = SseParser::new();
        let events = p.push(b": keepalive\n\ndata: x\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "x");
    }

    #[test]
    fn handles_crlf_and_split_crlf() {
        let mut p = SseParser::new();
        assert!(p.push(b"event: message\r").is_empty());
        let events = p.push(b"\ndata: y\r\n\r\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event, "message");
        assert_eq!(events[0].data, "y");
    }

    #[test]
    fn parses_multiple_events_in_one_chunk() {
        let mut p = SseParser::new();
        let events = p.push(b"data: a\n\ndata: b\n\n");
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].data, "a");
        assert_eq!(events[1].data, "b");
    }

    #[test]
    fn buffers_partial_event_across_chunks() {
        let mut p = SseParser::new();
        assert!(p.push(b"event: message\nda").is_empty());
        let events = p.push(b"ta: partial\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "partial");
    }

    #[test]
    fn tracks_event_id() {
        let mut p = SseParser::new();
        let events = p.push(b"id: 42\ndata: x\n\n");
        assert_eq!(events[0].id.as_deref(), Some("42"));
    }

    #[test]
    fn keeps_meaningful_leading_spaces() {
        let mut p = SseParser::new();
        // Exactly one space after the colon is stripped; the second is data.
        let events = p.push(b"data:  two-space\n\n");
        assert_eq!(events[0].data, " two-space");
    }

    #[test]
    fn parses_multibyte_payload_in_one_chunk() {
        let mut p = SseParser::new();
        let events = p.push("data: 中文😀\n\n".as_bytes());
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "中文😀");
    }

    #[test]
    fn decodes_utf8_split_by_every_byte_boundary() {
        let wire = "event: 消息\ndata: {\"text\":\"中文😀值\"}\n\n";
        let bytes = wire.as_bytes();
        let mut p = SseParser::new();
        let mut events = Vec::new();
        for b in bytes {
            events.extend(p.push(std::slice::from_ref(b)));
        }
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].event, "消息");
        assert_eq!(events[0].data, "{\"text\":\"中文😀值\"}");
    }

    #[test]
    fn decodes_utf8_split_at_every_split_point() {
        let wire = "id: 会话1\ndata: 中文😀\n\n".as_bytes();
        for split in 0..=wire.len() {
            let mut p = SseParser::new();
            let mut events = p.push(&wire[..split]);
            events.extend(p.push(&wire[split..]));
            assert_eq!(events.len(), 1, "split at {split}");
            assert_eq!(events[0].id.as_deref(), Some("会话1"), "split at {split}");
            assert_eq!(events[0].data, "中文😀", "split at {split}");
            assert!(!events[0].data.contains('\u{FFFD}'), "split at {split}");
        }
    }

    #[test]
    fn decodes_utf8_across_crlf_and_multiple_chunks() {
        let mut p = SseParser::new();
        assert!(p.push("data: 汉\r".as_bytes()).is_empty());
        let events = p.push("\n\r\n".as_bytes());
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "汉");
    }

    #[test]
    fn strips_leading_bom() {
        let mut p = SseParser::new();
        let events = p.push(b"\xEF\xBB\xBFdata: x\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "x");
    }

    #[test]
    fn strips_leading_bom_split_across_chunks() {
        let mut p = SseParser::new();
        assert!(p.push(b"\xEF").is_empty());
        assert!(p.push(b"\xBB").is_empty());
        let events = p.push(b"\xBFdata: x\n\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "x");
    }

    #[test]
    fn empty_chunk_does_not_drop_pending_cr() {
        let mut p = SseParser::new();
        assert!(p.push(b"data: a\r").is_empty());
        // An empty chunk must not erase the "swallow next LF" state.
        assert!(p.push(b"").is_empty());
        let events = p.push(b"\ndata: b\r\n\r\n");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].data, "a\nb");
    }

    #[test]
    fn replaces_invalid_bytes_without_losing_lines() {
        let mut p = SseParser::new();
        let events = p.push(b"data: \xFF\n\ndata: ok\n\n");
        assert_eq!(events.len(), 2);
        assert!(events[0].data.contains('\u{FFFD}'));
        assert_eq!(events[1].data, "ok");
    }
}
