use regex::Regex;

pub fn built_in_pattern(entity_type: &str) -> Option<&'static str> {
    match entity_type {
        // Inner patterns only — boundaries validated in `match_has_valid_boundaries`
        // (Rust `regex` crate does not support look-behind / look-ahead).
        "idcard_cn" => Some(
            r"(?x)
            [1-9]\d{5}
            (?:19|20)\d{2}
            (?:0[1-9]|1[0-2])
            (?:0[1-9]|[12]\d|3[01])
            \d{3}[\dXx]",
        ),
        "phone_cn" => Some(r"1[3-9]\d{9}"),
        "email" => Some(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
        "bank_card_cn" => Some(r"(?:\d[ -]*?){13,19}"),
        _ => None,
    }
}

pub fn compile_entity_regex(entity_type: &str, custom_pattern: Option<&str>) -> Option<Regex> {
    let pat = if entity_type == "custom_regex" {
        custom_pattern?
    } else {
        built_in_pattern(entity_type)?
    };
    Regex::new(pat).ok()
}

fn char_before(content: &str, start: usize) -> Option<char> {
    if start == 0 {
        return None;
    }
    content[..start].chars().next_back()
}

fn char_after(content: &str, end: usize) -> Option<char> {
    content[end..].chars().next()
}

fn not_adjacent_ascii_digit(content: &str, start: usize, end: usize) -> bool {
    !char_before(content, start).is_some_and(|c| c.is_ascii_digit())
        && !char_after(content, end).is_some_and(|c| c.is_ascii_digit())
}

fn email_local_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '%' | '+' | '-')
}

fn email_tail_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '.' | '-')
}

/// ASCII/CJK-friendly boundaries for built-in entity types (replaces `\b` in prior patterns).
pub fn match_has_valid_boundaries(
    entity_type: &str,
    content: &str,
    start: usize,
    end: usize,
) -> bool {
    match entity_type {
        "phone_cn" | "bank_card_cn" => not_adjacent_ascii_digit(content, start, end),
        "idcard_cn" => {
            not_adjacent_ascii_digit(content, start, end)
                && !char_after(content, end)
                    .is_some_and(|c| c.is_ascii_digit() || matches!(c, 'X' | 'x'))
        }
        "email" => {
            !char_before(content, start).is_some_and(email_local_char)
                && !char_after(content, end).is_some_and(email_tail_char)
        }
        _ => true,
    }
}

pub fn normalize_bank_card(raw: &str) -> String {
    raw.chars().filter(|c| c.is_ascii_digit()).collect()
}

pub fn luhn_valid(digits: &str) -> bool {
    if digits.len() < 13 || digits.len() > 19 {
        return false;
    }
    if !digits.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    let mut sum = 0u32;
    let mut alt = false;
    for ch in digits.chars().rev() {
        let mut n = ch.to_digit(10).unwrap_or(0);
        if alt {
            n *= 2;
            if n > 9 {
                n -= 9;
            }
        }
        sum += n;
        alt = !alt;
    }
    sum.is_multiple_of(10)
}

pub fn default_mask_prefix(entity_type: &str) -> &'static str {
    match entity_type {
        "idcard_cn" => "VIRBIUS_IDCARD_CN",
        "phone_cn" => "VIRBIUS_PHONE_CN",
        "email" => "VIRBIUS_EMAIL",
        "bank_card_cn" => "VIRBIUS_BANK_CARD",
        _ => "VIRBIUS_CUSTOM",
    }
}

pub fn mask_template_for(entity_type: &str, custom: Option<&str>) -> String {
    if let Some(t) = custom.filter(|s| !s.is_empty()) {
        return t.to_string();
    }
    format!("{{{{{}_{{seq}}}}}}", default_mask_prefix(entity_type))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn first_valid_match(entity_type: &str, text: &str) -> Option<String> {
        let re = compile_entity_regex(entity_type, None)?;
        for m in re.find_iter(text) {
            if match_has_valid_boundaries(entity_type, text, m.start(), m.end()) {
                return Some(m.as_str().to_string());
            }
        }
        None
    }

    #[test]
    fn phone_cn_matches_after_cjk_without_space() {
        assert_eq!(
            first_valid_match("phone_cn", "Please call 13912345678 for service"),
            Some("13912345678".into())
        );
    }

    #[test]
    fn phone_cn_still_matches_with_spaces() {
        assert_eq!(
            first_valid_match("phone_cn", "call 13800138000 please"),
            Some("13800138000".into())
        );
    }

    #[test]
    fn idcard_cn_matches_after_cjk() {
        assert_eq!(
            first_valid_match("idcard_cn", "ID number 110101199003077934"),
            Some("110101199003077934".into())
        );
    }

    #[test]
    fn email_matches_after_cjk() {
        assert_eq!(
            first_valid_match("email", "Contact admin@example.com thanks"),
            Some("admin@example.com".into())
        );
    }

    #[test]
    fn phone_cn_does_not_match_substring_inside_long_digits() {
        assert_eq!(
            first_valid_match("phone_cn", "20240101139123456789012"),
            None
        );
    }
}
