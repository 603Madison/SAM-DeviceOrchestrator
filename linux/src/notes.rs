//! Which of this computer's notifications go to the person's other devices, and how they read there.

use std::collections::HashMap;
use std::time::{Duration, Instant};

/// The name our own notifications carry, and the hint that marks one mirrored from another device.
pub const OWN_DESKTOP_ENTRY: &str = "app.superawesomemagical.Orchestrator";
pub const MIRRORED_HINT: &str = "x-sam-mirrored";

const MAX_TITLE: usize = 200;
const MAX_TEXT: usize = 2000;
const MAX_APP: usize = 100;

/// A notification as an app asked for it (`org.freedesktop.Notifications.Notify`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Seen {
    pub app_name: String,
    pub summary: String,
    pub body: String,
    /// The `desktop-entry` hint.
    pub desktop_entry: String,
    /// The `transient` hint: it isn't meant to be kept.
    pub transient: bool,
    /// The `x-sam-mirrored` hint: it came from another device.
    pub mirrored: bool,
    /// The agent itself raised it.
    pub own: bool,
}

/// What goes to the other devices for a notification.
#[derive(Debug, Clone, PartialEq)]
pub struct Outgoing {
    pub app: String,
    pub app_id: String,
    pub title: String,
    pub text: String,
}

/// What to send for a notification, or `None` when it must never be mirrored: the agent's own,
/// one that was itself mirrored (no loops), a transient one, one from an app the person
/// excluded, or an empty one.
pub fn outgoing(seen: &Seen, never: &[String]) -> Option<Outgoing> {
    if seen.own || seen.mirrored || seen.transient || seen.desktop_entry == OWN_DESKTOP_ENTRY {
        return None;
    }
    let title = clip(&plain(&seen.summary), MAX_TITLE);
    let text = clip(&strip_markup(&seen.body), MAX_TEXT);
    if title.is_empty() && text.is_empty() {
        return None;
    }
    let app_name = plain(&seen.app_name);
    let app_id = plain(&seen.desktop_entry);
    let excluded = |name: &str| !name.is_empty() && never.iter().any(|n| n.trim().to_lowercase() == name.to_lowercase());
    if excluded(&app_name) || excluded(&app_id) {
        return None;
    }
    let app = if !app_name.is_empty() { app_name } else { app_id.rsplit('.').next().unwrap_or("").to_string() };
    Some(Outgoing { app: clip(&app, MAX_APP), app_id: clip(&app_id, MAX_APP), title, text })
}

/// One line of plain words: control characters out, space at the ends off.
fn plain(text: &str) -> String {
    text.chars().map(|c| if c.is_control() { ' ' } else { c }).collect::<String>().trim().to_string()
}

fn clip(text: &str, most: usize) -> String {
    if text.chars().count() <= most {
        return text.to_string();
    }
    let mut cut: String = text.chars().take(most - 1).collect();
    cut.push('…');
    cut
}

/// A notification body as plain words: the markup the notification spec allows (`<b>`, `<i>`,
/// `<u>`, `<a href>`, `<img>`) taken out, entities read, line breaks kept. A `<` that doesn't
/// start a tag stays ("1 < 2").
pub fn strip_markup(body: &str) -> String {
    let mut out = String::with_capacity(body.len());
    let mut rest = body;
    while let Some(at) = rest.find(['<', '&']) {
        out.push_str(&rest[..at]);
        let tail = &rest[at..];
        if tail.starts_with('<') {
            match tag_length(tail) {
                Some((length, name)) => {
                    if name == "br" || name == "p" || name == "div" {
                        out.push('\n');
                    }
                    rest = &tail[length..];
                }
                None => {
                    out.push('<');
                    rest = &tail[1..];
                }
            }
        } else {
            match entity(tail) {
                Some((length, c)) => {
                    out.push(c);
                    rest = &tail[length..];
                }
                None => {
                    out.push('&');
                    rest = &tail[1..];
                }
            }
        }
    }
    out.push_str(rest);
    let cleaned: String = out.chars().filter(|c| *c == '\n' || !c.is_control()).collect();
    let lines: Vec<&str> = cleaned.lines().map(str::trim).collect();
    let mut joined = String::new();
    let mut blank = true;
    for line in lines {
        if line.is_empty() {
            if !blank {
                joined.push('\n');
            }
            blank = true;
        } else {
            joined.push_str(line);
            joined.push('\n');
            blank = false;
        }
    }
    joined.trim().to_string()
}

/// The length of the tag `text` starts with and its lowercase name, if it starts with one.
fn tag_length(text: &str) -> Option<(usize, String)> {
    let inner = text[1..].strip_prefix('/').unwrap_or(&text[1..]);
    if !inner.starts_with(|c: char| c.is_ascii_alphabetic()) {
        return None;
    }
    let end = text.find('>')?;
    // A tag doesn't hold another "<": "a <b and c>" isn't one.
    if text[1..end].contains('<') {
        return None;
    }
    let name: String = inner.chars().take_while(|c| c.is_ascii_alphanumeric()).collect::<String>().to_lowercase();
    Some((end + 1, name))
}

fn entity(text: &str) -> Option<(usize, char)> {
    let end = text.find(';').filter(|end| *end <= 10)?;
    let name = &text[1..end];
    let c = match name {
        "amp" => '&',
        "lt" => '<',
        "gt" => '>',
        "quot" => '"',
        "apos" => '\'',
        "nbsp" => ' ',
        _ => {
            let number = name.strip_prefix('#')?;
            let code = match number.strip_prefix(['x', 'X']) {
                Some(hex) => u32::from_str_radix(hex, 16).ok()?,
                None => number.parse().ok()?,
            };
            char::from_u32(code)?
        }
    };
    Some((end + 1, c))
}

/// Plain words made safe to show where the notification daemon reads markup.
pub fn escape_markup(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

/// Holds back notifications that change faster than anyone can read them (a progress bar
/// updating ten times a second): each one is sent at most once per [`Coalesce::EVERY`], and the
/// latest of what arrived in between goes when that time is up.
pub struct Coalesce<T> {
    entries: HashMap<String, Entry<T>>,
}

struct Entry<T> {
    sent_at: Instant,
    waiting: Option<T>,
}

#[derive(Debug, PartialEq)]
pub enum Offer<T> {
    /// Send it now.
    Send(T),
    /// It's kept; ask again with [`Coalesce::due`] after this long (a timer is needed).
    Wait(Duration),
    /// It's kept, replacing what was waiting; the timer already running will take it.
    Replaced,
}

impl<T> Default for Coalesce<T> {
    fn default() -> Self {
        Self { entries: HashMap::new() }
    }
}

impl<T> Coalesce<T> {
    pub const EVERY: Duration = Duration::from_secs(2);
    const MOST: usize = 512;

    pub fn offer(&mut self, key: &str, value: T, now: Instant) -> Offer<T> {
        match self.entries.get_mut(key) {
            Some(entry) if now.duration_since(entry.sent_at) < Self::EVERY => {
                let first = entry.waiting.is_none();
                entry.waiting = Some(value);
                if first { Offer::Wait(Self::EVERY - now.duration_since(entry.sent_at)) } else { Offer::Replaced }
            }
            _ => {
                if self.entries.len() >= Self::MOST {
                    let cutoff = now.checked_sub(Self::EVERY * 30).unwrap_or(now);
                    self.entries.retain(|_, e| e.waiting.is_some() || e.sent_at > cutoff);
                }
                self.entries.insert(key.to_string(), Entry { sent_at: now, waiting: None });
                Offer::Send(value)
            }
        }
    }

    /// What was waiting for `key`, now that its time is up.
    pub fn due(&mut self, key: &str, now: Instant) -> Option<T> {
        let entry = self.entries.get_mut(key)?;
        let value = entry.waiting.take()?;
        entry.sent_at = now;
        Some(value)
    }

    /// The notification is gone: forget it. Whether anything was ever sent for it.
    pub fn close(&mut self, key: &str) -> bool {
        self.entries.remove(key).is_some()
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seen(app: &str, summary: &str, body: &str) -> Seen {
        Seen { app_name: app.into(), summary: summary.into(), body: body.into(), ..Default::default() }
    }

    #[test]
    fn an_ordinary_notification_is_mirrored() {
        let out = outgoing(&Seen { desktop_entry: "org.signal.Signal".into(), ..seen("Signal", "Sam", "On my <b>way</b>") }, &[]).unwrap();
        assert_eq!(out, Outgoing { app: "Signal".into(), app_id: "org.signal.Signal".into(), title: "Sam".into(), text: "On my way".into() });
        // No app name: the last part of the desktop entry stands in.
        let out = outgoing(&Seen { desktop_entry: "org.gnome.Calendar".into(), ..seen("", "Dentist", "") }, &[]).unwrap();
        assert_eq!(out.app, "Calendar");
    }

    #[test]
    fn our_own_mirrored_transient_excluded_and_empty_ones_never_are() {
        let base = seen("Signal", "Sam", "hi");
        assert!(outgoing(&base, &[]).is_some());
        assert!(outgoing(&Seen { own: true, ..base.clone() }, &[]).is_none(), "the agent's own");
        assert!(outgoing(&Seen { mirrored: true, ..base.clone() }, &[]).is_none(), "one that was mirrored here");
        assert!(outgoing(&Seen { transient: true, ..base.clone() }, &[]).is_none(), "a transient one");
        assert!(outgoing(&Seen { desktop_entry: OWN_DESKTOP_ENTRY.into(), ..base.clone() }, &[]).is_none(), "ours by name");
        assert!(outgoing(&base, &["signal".into()]).is_none(), "an excluded app, whatever the capitals");
        assert!(outgoing(&base, &[" Signal ".into()]).is_none());
        assert!(outgoing(&Seen { desktop_entry: "org.signal.Signal".into(), ..seen("Messages", "Sam", "hi") }, &["org.signal.signal".into()]).is_none(), "excluded by its id");
        assert!(outgoing(&base, &["Signal Beta".into(), "".into()]).is_some(), "only the apps named");
        assert!(outgoing(&seen("Signal", "", ""), &[]).is_none(), "an empty one");
        assert!(outgoing(&seen("Signal", "  ", "<b></b>\n"), &[]).is_none(), "one that's empty once the markup is out");
        assert!(outgoing(&seen("Signal", "", "just a body"), &[]).is_some());
    }

    #[test]
    fn long_ones_are_cut() {
        let out = outgoing(&seen("App", &"t".repeat(500), &"b".repeat(50_000)), &[]).unwrap();
        assert_eq!(out.title.chars().count(), 200);
        assert_eq!(out.text.chars().count(), 2000);
        assert!(out.text.ends_with('…'));
    }

    #[test]
    fn markup_is_stripped() {
        assert_eq!(strip_markup("<b>Bold</b> and <i>italic</i> and <u>under</u>"), "Bold and italic and under");
        assert_eq!(strip_markup(r#"See <a href="https://example.com/?a=1&amp;b=2">this page</a>"#), "See this page");
        assert_eq!(strip_markup(r#"Photo <img src="file:///x.png" alt="cat"/> here"#), "Photo  here");
        assert_eq!(strip_markup("Tom &amp; Jerry &lt;3 &quot;cheese&quot; &apos;now&apos; &#33; &#x2728;"), "Tom & Jerry <3 \"cheese\" 'now' ! ✨");
        assert_eq!(strip_markup("1 < 2 and 3 > 2"), "1 < 2 and 3 > 2");
        assert_eq!(strip_markup("a <b and c>"), "a");
        assert_eq!(strip_markup("x < y <b>z</b>"), "x < y z");
        assert_eq!(strip_markup("AT&T &unknown; & more &#xZZ;"), "AT&T &unknown; & more &#xZZ;");
        assert_eq!(strip_markup("one<br>two<br/>three"), "one\ntwo\nthree");
        assert_eq!(strip_markup("  line one  \n\n\n\n line two \u{7} "), "line one\n\nline two");
        assert_eq!(strip_markup("unclosed <b tag"), "unclosed <b tag");
        assert_eq!(strip_markup("<script>alert(1)</script>"), "alert(1)");
        assert_eq!(strip_markup("&lt;b&gt;not a tag&lt;/b&gt;"), "<b>not a tag</b>");
        assert_eq!(strip_markup(""), "");
        assert_eq!(strip_markup("héllo <b>wörld</b> ✨"), "héllo wörld ✨");
    }

    #[test]
    fn plain_words_are_escaped_for_showing() {
        assert_eq!(escape_markup("<b>Tom & Jerry</b>"), "&lt;b&gt;Tom &amp; Jerry&lt;/b&gt;");
        assert_eq!(strip_markup(&escape_markup("1 < 2 & <b>x</b>")), "1 < 2 & <b>x</b>");
    }

    #[test]
    fn fast_updates_are_coalesced() {
        let start = Instant::now();
        let ms = |n| start + Duration::from_millis(n);
        let mut c = Coalesce::default();
        assert_eq!(c.offer("7", 0, ms(0)), Offer::Send(0));
        // Ten a second for the next two seconds: one timer, the rest replace what's waiting.
        assert_eq!(c.offer("7", 1, ms(100)), Offer::Wait(Duration::from_millis(1900)));
        for n in 2..20 {
            assert_eq!(c.offer("7", n, ms(n * 100)), Offer::Replaced);
        }
        // Another notification isn't held up by this one.
        assert_eq!(c.offer("8", 100, ms(150)), Offer::Send(100));
        // The timer fires: the latest goes, once.
        assert_eq!(c.due("7", ms(2000)), Some(19));
        assert_eq!(c.due("7", ms(2001)), None);
        // And the next quiet period starts from there.
        assert_eq!(c.offer("7", 20, ms(2100)), Offer::Wait(Duration::from_millis(1900)));
        assert_eq!(c.due("7", ms(4000)), Some(20));
        assert_eq!(c.offer("7", 21, ms(6100)), Offer::Send(21));
        // Closed: forgotten, and a waiting update never goes.
        assert_eq!(c.offer("7", 22, ms(6200)), Offer::Wait(Duration::from_millis(1900)));
        assert!(c.close("7"));
        assert_eq!(c.due("7", ms(8100)), None);
        assert!(!c.close("never-seen"));
    }
}
