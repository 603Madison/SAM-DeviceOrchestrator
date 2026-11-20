//! Which links from other devices get opened here. Anything not on this short list is refused.

use url::Url;

/// The family's own link schemes, opened when an installed app handles them. The one place they're listed.
pub const FAMILY_SCHEMES: &[&str] = &["samvideo", "sammusic", "samemail", "samaps", "samhealth"];

const MAX_LENGTH: usize = 4096;

#[derive(Debug, PartialEq, Eq)]
pub enum Checked {
    /// A web address: the browser opens it.
    Web(String),
    /// One of the family's app links (the scheme, the address).
    Family(&'static str, String),
}

impl Checked {
    pub fn address(&self) -> &str {
        match self {
            Checked::Web(a) | Checked::Family(_, a) => a,
        }
    }
}

/// The link to open for `raw`, or why not. What comes back is the parsed address written out
/// again, so it always starts with its scheme: nothing `xdg-open` could read as an option, and
/// nothing a shell is ever shown (it's spawned directly).
pub fn check(raw: &str) -> Result<Checked, &'static str> {
    if raw.is_empty() || raw.len() > MAX_LENGTH {
        return Err("it isn't a link");
    }
    // Before parsing: the URL parser quietly drops tabs and newlines and trims spaces, which is
    // how a link ends up looking like one thing and being another.
    if raw.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return Err("it has spaces or control characters in it");
    }
    let url = Url::parse(raw).map_err(|_| "it isn't a link")?;
    let address = url.as_str().to_string();
    match url.scheme() {
        "http" | "https" => {
            if url.host_str().is_none_or(str::is_empty) {
                return Err("it has no site in it");
            }
            if !url.username().is_empty() || url.password().is_some() {
                return Err("it carries a username or password");
            }
            Ok(Checked::Web(address))
        }
        scheme => match FAMILY_SCHEMES.iter().find(|s| **s == scheme) {
            Some(family) => Ok(Checked::Family(family, address)),
            None => Err("only web links and the family's apps' links are opened"),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn web_links_are_opened() {
        assert_eq!(check("https://example.com/a?b=c#d"), Ok(Checked::Web("https://example.com/a?b=c#d".into())));
        assert_eq!(check("http://192.168.1.2:8080/"), Ok(Checked::Web("http://192.168.1.2:8080/".into())));
        // Written out again from what was parsed, scheme first.
        assert_eq!(check("HTTPS://Example.COM").unwrap().address(), "https://example.com/");
    }

    #[test]
    fn the_familys_links_are_opened() {
        assert_eq!(check("sammusic://track/12"), Ok(Checked::Family("sammusic", "sammusic://track/12".into())));
        for scheme in FAMILY_SCHEMES {
            assert!(matches!(check(&format!("{scheme}://x/1")), Ok(Checked::Family(s, _)) if s == *scheme));
        }
    }

    #[test]
    fn everything_else_is_refused() {
        for hostile in [
            "",
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "file:///etc/passwd",
            "file://localhost/etc/passwd",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:x",
            "smb://server/share",
            "ssh://host",
            "mailto:someone@example.com",
            "tel:911",
            "intent://x#Intent;end",
            "samother://x",
            "sam music://x",
            " https://example.com",
            "https://example.com ",
            "https://exa\tmple.com",
            "https://example.com/\n--version",
            "https://example.com/\r\nX: y",
            "https://example.com/\u{0}",
            "\u{7f}https://example.com",
            "https://example.com/\u{85}",
            "ht\ttps://example.com",
            "java\nscript:alert(1)",
            "-https://example.com",
            "--help",
            "-",
            "--",
            "--version https://example.com",
            "/etc/passwd",
            "./file.desktop",
            "~/x",
            "example.com",
            "//example.com",
            "https://",
            "https:///path",
            "http://user:pw@example.com/",
            "https://bank.example@evil.example/",
            "about:blank",
            "chrome://settings",
            "view-source:https://example.com",
            "blob:https://example.com/x",
            "x-scheme-handler/http",
            "$(reboot)",
            "`reboot`",
            "https://example.com/;reboot #",
        ] {
            let result = check(hostile);
            // The last one is a real (if odd) web address: what matters is that it's handed over
            // whole, as one argument starting with its scheme.
            if hostile == "https://example.com/;reboot #" {
                assert!(result.is_err(), "spaces are refused: {hostile:?}");
                continue;
            }
            assert!(result.is_err(), "{hostile:?} should be refused, got {result:?}");
        }
        assert!(check(&format!("https://example.com/{}", "a".repeat(5000))).is_err());
    }

    #[test]
    fn what_is_opened_can_never_be_read_as_an_option() {
        for ok in ["https://example.com/--help", "https://-x.example/", "http://example.com/?-=-", "samvideo://-/--", "https://example.com/;$(reboot)`x`"] {
            let address = check(ok).unwrap().address().to_string();
            assert!(!address.starts_with('-'), "{address}");
            assert!(address.starts_with("http://") || address.starts_with("https://") || FAMILY_SCHEMES.iter().any(|s| address.starts_with(&format!("{s}:"))));
            assert!(!address.chars().any(|c| c.is_control() || c.is_whitespace()));
        }
    }
}
