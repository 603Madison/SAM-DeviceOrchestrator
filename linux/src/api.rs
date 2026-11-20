//! The server's API as the agent uses it (`docs/PROTOCOL.md`), and what devices say to each other.
//! The shapes follow `shared/…/Orchestrator.kt`.

use std::time::Duration;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::seal::Sealed;

pub const CAPABILITIES: &[&str] = &["notifications", "clipboard", "links", "ring", "activity", "media"];

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub struct Battery {
    pub level: i32,
    #[serde(default)]
    pub charging: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub id: String,
    pub name: String,
    /// "LINUX", "ANDROID", "OTHER" (kept as text: a newer server may know more kinds).
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub model: String,
    pub public_key: String,
    #[serde(default)]
    pub capabilities: Vec<String>,
    #[serde(default)]
    pub online: bool,
    #[serde(default)]
    pub last_seen_at: i64,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub battery: Option<Battery>,
    #[serde(default)]
    pub current: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceRegistration<'a> {
    pub name: &'a str,
    pub kind: &'a str,
    pub model: &'a str,
    pub public_key: &'a str,
    pub capabilities: &'a [&'a str],
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    pub username: String,
    #[serde(default)]
    pub display_name: String,
}

#[derive(Debug, Deserialize)]
pub struct SignedIn {
    pub token: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct Me {
    pub user: User,
}

#[derive(Debug, Deserialize)]
pub struct Ticket {
    pub url: String,
    pub ticket: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct SentMessage {
    #[serde(default)]
    pub delivered: Vec<String>,
    #[serde(default)]
    pub queued: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IncomingMessage {
    pub id: String,
    /// The sending device; none from the web page.
    #[serde(default)]
    pub from: Option<String>,
    #[serde(default)]
    pub from_name: String,
    pub sealed: Sealed,
}

/// What the live line carries. Frames of a kind this version doesn't know are skipped.
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum StreamFrame {
    Message {
        message: IncomingMessage,
    },
    Devices {
        devices: Vec<Device>,
    },
    Ping,
    #[serde(other)]
    Unknown,
}

fn yes() -> bool {
    true
}

/// What devices say to each other, as JSON before sealing.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Payload {
    #[serde(rename_all = "camelCase")]
    Notification {
        key: String,
        app: String,
        #[serde(default)]
        app_id: String,
        #[serde(default)]
        title: String,
        #[serde(default)]
        text: String,
        at: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        icon: Option<String>,
    },
    Dismiss {
        key: String,
    },
    Clipboard {
        text: String,
    },
    Link {
        url: String,
        #[serde(default)]
        title: String,
    },
    Ring {
        #[serde(default = "yes")]
        on: bool,
    },
    Activity {
        app: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        title: Option<String>,
        #[serde(default)]
        detail: String,
        #[serde(default)]
        url: String,
        at: i64,
    },
    Media {
        action: String,
    },
}

pub const KNOWN_TYPES: &[&str] = &["notification", "dismiss", "clipboard", "link", "ring", "activity", "media"];

impl Payload {
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).expect("payloads are plain data")
    }
}

/// The payload in `bytes`: `Ok(None)` for a kind this version doesn't know (skipped, not an
/// error), `Err` for something that isn't a payload at all or is a known kind gone wrong.
pub fn parse_payload(bytes: &[u8]) -> Result<Option<Payload>, String> {
    let value: Value = serde_json::from_slice(bytes).map_err(|_| "not JSON".to_string())?;
    let Some(kind) = value.get("type").and_then(Value::as_str) else {
        return Err("no type".into());
    };
    if !KNOWN_TYPES.contains(&kind) {
        return Ok(None);
    }
    let kind = kind.to_string();
    serde_json::from_value(value)
        .map(Some)
        .map_err(|_| format!("a {kind} with missing or wrong fields"))
}

/// How a payload of this kind is sent: the capability a device needs to be sent it unasked, how
/// long the server keeps it for a device that's away, and whether a newer one replaces it.
pub struct Delivery {
    pub capability: Option<&'static str>,
    pub ttl_seconds: u64,
    pub collapse_key: Option<String>,
}

pub fn delivery(payload: &Value) -> Delivery {
    let kind = payload.get("type").and_then(Value::as_str).unwrap_or("");
    let (capability, ttl_seconds, collapse_key) = match kind {
        "notification" | "dismiss" => (Some("notifications"), 86_400, None),
        "clipboard" => (Some("clipboard"), 600, Some("clipboard".to_string())),
        "link" => (Some("links"), 604_800, None),
        "ring" => (Some("ring"), 300, None),
        "activity" => {
            let app: String = payload.get("app").and_then(Value::as_str).unwrap_or("").chars().take(48).collect();
            (Some("activity"), 900, Some(format!("activity:{app}")))
        }
        "media" => (Some("media"), 60, None),
        _ => (None, 86_400, None),
    };
    Delivery {
        capability,
        ttl_seconds,
        collapse_key,
    }
}

// ---- HTTP ----

#[derive(Debug)]
pub enum ApiError {
    /// 401: the token no longer works (signed out, or the device was removed).
    Unauthorized(String),
    /// Any other refusal, with the server's words for the person.
    Status(u16, String),
    /// The server couldn't be reached.
    Network(String),
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ApiError::Unauthorized(m) | ApiError::Status(_, m) | ApiError::Network(m) => f.write_str(m),
        }
    }
}

impl std::error::Error for ApiError {}

pub fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("sam-orchestrator/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(30))
        // A token never follows a redirect to somewhere else.
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("the HTTP client's settings are valid")
}

/// One server, as one signed-in device (or nobody yet).
#[derive(Clone)]
pub struct Api {
    http: reqwest::Client,
    base: String,
    token: Option<String>,
}

impl Api {
    pub fn new(http: &reqwest::Client, base: &str, token: Option<&str>) -> Self {
        Self {
            http: http.clone(),
            base: base.trim_end_matches('/').to_string(),
            token: token.map(str::to_string),
        }
    }

    pub fn token(&self) -> Option<&str> {
        self.token.as_deref()
    }

    pub async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T, ApiError> {
        self.send(self.http.get(format!("{}{path}", self.base))).await
    }

    pub async fn post<B: Serialize + ?Sized, T: DeserializeOwned>(&self, path: &str, body: &B) -> Result<T, ApiError> {
        self.send(self.http.post(format!("{}{path}", self.base)).json(body)).await
    }

    pub async fn delete(&self, path: &str) -> Result<Value, ApiError> {
        self.send(self.http.delete(format!("{}{path}", self.base))).await
    }

    async fn send<T: DeserializeOwned>(&self, mut request: reqwest::RequestBuilder) -> Result<T, ApiError> {
        if let Some(token) = &self.token {
            request = request.bearer_auth(token);
        }
        let response = request
            .send()
            .await
            .map_err(|e| ApiError::Network(unreachable_words(&self.base, &e)))?;
        let status = response.status();
        let bytes = response
            .bytes()
            .await
            .map_err(|e| ApiError::Network(unreachable_words(&self.base, &e)))?;
        if status.is_success() {
            return serde_json::from_slice(&bytes)
                .map_err(|_| ApiError::Status(status.as_u16(), "The server answered with something unexpected".into()));
        }
        let words = serde_json::from_slice::<Value>(&bytes)
            .ok()
            .and_then(|v| v.get("error").and_then(Value::as_str).map(str::to_string))
            .unwrap_or_else(|| match status.as_u16() {
                401 => "Signed out".to_string(),
                301..=308 => "The server sent us somewhere else: check its address (https?)".to_string(),
                code => format!("The server said no ({code})"),
            });
        Err(if status.as_u16() == 401 {
            ApiError::Unauthorized(words)
        } else {
            ApiError::Status(status.as_u16(), words)
        })
    }
}

fn unreachable_words(base: &str, error: &reqwest::Error) -> String {
    // What went wrong underneath, as the libraries describe it (never the request's content).
    let causes = format!("{error:?}").to_lowercase();
    let why = if causes.contains("certificate") || causes.contains("unknownissuer") {
        "its certificate isn't one this computer trusts"
    } else if error.is_timeout() {
        "it took too long to answer"
    } else if error.is_connect() {
        "couldn't connect"
    } else {
        "the connection failed"
    };
    format!("Couldn't reach {base}: {why}")
}

/// A server address as typed, tidied: `https://` (or `http://`, which the caller warns about),
/// no credentials, query or fragment, no trailing slash.
pub fn server_address(typed: &str) -> Result<String, String> {
    let typed = typed.trim();
    if typed.is_empty() {
        return Err("Which server? Give its address, like https://orchestrator.example.com".into());
    }
    if typed.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return Err("That isn't a server address".into());
    }
    let with_scheme = if typed.contains("://") {
        typed.to_string()
    } else {
        format!("https://{typed}")
    };
    let url = url::Url::parse(&with_scheme).map_err(|_| "That isn't a server address".to_string())?;
    if !matches!(url.scheme(), "https" | "http") {
        return Err("A server's address starts with https:// (or http:// at home)".into());
    }
    if url.host_str().is_none_or(str::is_empty) || !url.username().is_empty() || url.password().is_some() {
        return Err("That isn't a server address".into());
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err("A server's address has no ? or # in it".into());
    }
    Ok(url.as_str().trim_end_matches('/').to_string())
}

/// Whether what's said to this server crosses the network unencrypted: plain http to anywhere
/// but this computer itself.
pub fn is_plain_http(server: &str) -> bool {
    let Ok(url) = url::Url::parse(server) else { return false };
    let here = match url.host() {
        Some(url::Host::Domain(name)) => name.eq_ignore_ascii_case("localhost"),
        Some(url::Host::Ipv4(address)) => address.is_loopback(),
        Some(url::Host::Ipv6(address)) => address.is_loopback(),
        None => false,
    };
    url.scheme() == "http" && !here
}

/// The live line's address for a server.
pub fn stream_address(server: &str) -> String {
    let rest = server.trim_end_matches('/');
    match rest.strip_prefix("https://") {
        Some(host) => format!("wss://{host}/api/stream"),
        None => format!("ws://{}/api/stream", rest.strip_prefix("http://").unwrap_or(rest)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn payloads_are_written_as_kotlin_writes_them() {
        let n = Payload::Notification {
            key: "42".into(),
            app: "Signal".into(),
            app_id: "org.signal".into(),
            title: "Sam".into(),
            text: "hi".into(),
            at: 5,
            icon: None,
        };
        assert_eq!(
            n.to_json(),
            r#"{"type":"notification","key":"42","app":"Signal","appId":"org.signal","title":"Sam","text":"hi","at":5}"#
        );
        assert_eq!(Payload::Dismiss { key: "42".into() }.to_json(), r#"{"type":"dismiss","key":"42"}"#);
        assert_eq!(
            Payload::Clipboard { text: "x".into() }.to_json(),
            r#"{"type":"clipboard","text":"x"}"#
        );
        assert_eq!(
            Payload::Link {
                url: "https://a.example/".into(),
                title: "".into()
            }
            .to_json(),
            r#"{"type":"link","url":"https://a.example/","title":""}"#
        );
        assert_eq!(Payload::Ring { on: false }.to_json(), r#"{"type":"ring","on":false}"#);
        let a = Payload::Activity {
            app: "music".into(),
            title: Some("Song".into()),
            detail: "Artist".into(),
            url: "sammusic://t/1".into(),
            at: 9,
        };
        assert_eq!(
            a.to_json(),
            r#"{"type":"activity","app":"music","title":"Song","detail":"Artist","url":"sammusic://t/1","at":9}"#
        );
        // A stopped activity has no title at all (explicitNulls = false).
        let stopped = Payload::Activity {
            app: "music".into(),
            title: None,
            detail: "".into(),
            url: "".into(),
            at: 9,
        };
        assert_eq!(
            stopped.to_json(),
            r#"{"type":"activity","app":"music","detail":"","url":"","at":9}"#
        );
        assert_eq!(
            Payload::Media { action: "toggle".into() }.to_json(),
            r#"{"type":"media","action":"toggle"}"#
        );
    }

    #[test]
    fn payloads_are_read_as_kotlin_writes_them() {
        // Kotlin's output for the protocol's vector, and with defaults left out or nulls spelled out.
        assert_eq!(
            parse_payload(br#"{"type":"clipboard","text":"hello from the aurora"}"#).unwrap(),
            Some(Payload::Clipboard {
                text: "hello from the aurora".into()
            })
        );
        assert_eq!(
            parse_payload(br#"{"type":"notification","key":"k","app":"Signal","at":1}"#).unwrap(),
            Some(Payload::Notification {
                key: "k".into(),
                app: "Signal".into(),
                app_id: "".into(),
                title: "".into(),
                text: "".into(),
                at: 1,
                icon: None
            })
        );
        assert_eq!(parse_payload(br#"{"type":"ring"}"#).unwrap(), Some(Payload::Ring { on: true }));
        assert_eq!(
            parse_payload(br#"{"type":"link","url":"https://x.example"}"#).unwrap(),
            Some(Payload::Link {
                url: "https://x.example".into(),
                title: "".into()
            })
        );
        assert_eq!(
            parse_payload(br#"{"type":"activity","app":"video","title":null,"at":3,"somethingNew":true}"#).unwrap(),
            Some(Payload::Activity {
                app: "video".into(),
                title: None,
                detail: "".into(),
                url: "".into(),
                at: 3
            })
        );
    }

    #[test]
    fn unknown_kinds_are_skipped_and_nonsense_is_an_error() {
        assert_eq!(parse_payload(br#"{"type":"hologram","beam":1}"#).unwrap(), None);
        assert!(parse_payload(b"not json").is_err());
        assert!(parse_payload(br#"{"text":"no type"}"#).is_err());
        assert!(parse_payload(br#"{"type":"clipboard"}"#).is_err());
        assert!(parse_payload(br#"{"type":7}"#).is_err());
    }

    #[test]
    fn frames_are_read_and_unknown_ones_skipped() {
        let frame: StreamFrame = serde_json::from_value(
            json!({"type":"message","message":{"id":"m1","from":"d2","fromName":"Pixel","sentAt":7,
            "sealed":{"epk":"a","nonce":"b","ct":"c"}}}),
        )
        .unwrap();
        match frame {
            StreamFrame::Message { message } => {
                assert_eq!(
                    (message.id.as_str(), message.from.as_deref(), message.from_name.as_str()),
                    ("m1", Some("d2"), "Pixel")
                );
            }
            other => panic!("{other:?}"),
        }
        // From the web page: no sending device.
        let web: StreamFrame = serde_json::from_value(json!({"type":"message","message":{"id":"m2","fromName":"The web page","sentAt":7,
            "sealed":{"epk":"a","nonce":"b","ct":"c"}}}))
        .unwrap();
        assert!(matches!(web, StreamFrame::Message { message } if message.from.is_none()));
        let devices: StreamFrame = serde_json::from_value(
            json!({"type":"devices","devices":[{"id":"d1","name":"Laptop","kind":"LINUX","publicKey":"k",
            "capabilities":["links"],"online":true,"lastSeenAt":1,"createdAt":1,"battery":{"level":81,"charging":true},"current":true}]}),
        )
        .unwrap();
        assert!(matches!(devices, StreamFrame::Devices { devices } if devices[0].battery == Some(Battery { level: 81, charging: true })));
        assert!(matches!(
            serde_json::from_str::<StreamFrame>(r#"{"type":"ping"}"#).unwrap(),
            StreamFrame::Ping
        ));
        assert!(matches!(
            serde_json::from_str::<StreamFrame>(r#"{"type":"later","x":1}"#).unwrap(),
            StreamFrame::Unknown
        ));
    }

    #[test]
    fn each_kind_is_sent_as_the_protocol_says() {
        let d = delivery(&json!({"type":"clipboard","text":"x"}));
        assert_eq!(
            (d.capability, d.ttl_seconds, d.collapse_key.as_deref()),
            (Some("clipboard"), 600, Some("clipboard"))
        );
        let d = delivery(&json!({"type":"activity","app":"music","at":1}));
        assert_eq!(
            (d.capability, d.ttl_seconds, d.collapse_key.as_deref()),
            (Some("activity"), 900, Some("activity:music"))
        );
        let d = delivery(&json!({"type":"notification"}));
        assert_eq!((d.capability, d.ttl_seconds, d.collapse_key), (Some("notifications"), 86_400, None));
        assert_eq!(delivery(&json!({"type":"link"})).ttl_seconds, 604_800);
        assert_eq!(delivery(&json!({"type":"ring"})).ttl_seconds, 300);
        assert_eq!(delivery(&json!({"type":"dismiss"})).ttl_seconds, 86_400);
        assert!(delivery(&json!({"type":"hologram"})).capability.is_none());
    }

    #[test]
    fn server_addresses_are_tidied_and_checked() {
        assert_eq!(server_address(" https://o.example.com/ ").unwrap(), "https://o.example.com");
        assert_eq!(server_address("o.example.com").unwrap(), "https://o.example.com");
        assert_eq!(server_address("http://192.168.1.5:8080").unwrap(), "http://192.168.1.5:8080");
        assert_eq!(
            server_address("https://example.com/orchestrator/").unwrap(),
            "https://example.com/orchestrator"
        );
        assert!(is_plain_http("http://192.168.1.5:8080") && !is_plain_http("https://o.example.com"));
        assert!(is_plain_http("http://orchestrator.lan") && is_plain_http("http://localhost.example.com"));
        for here in [
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://LOCALHOST",
            "http://[::1]:8080",
            "http://127.0.0.5",
        ] {
            assert!(!is_plain_http(here), "no warning for this computer itself: {here}");
        }
        for bad in [
            "",
            "ftp://x.example",
            "file:///etc/passwd",
            "https://user:pw@x.example",
            "https://x.example/?a=1",
            "https://x.example/#f",
            "https://x .example",
            "javascript:alert(1)",
        ] {
            assert!(server_address(bad).is_err(), "{bad}");
        }
        assert_eq!(stream_address("https://o.example.com"), "wss://o.example.com/api/stream");
        assert_eq!(stream_address("http://127.0.0.1:8080"), "ws://127.0.0.1:8080/api/stream");
    }
}
