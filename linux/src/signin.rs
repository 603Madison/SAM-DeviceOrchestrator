//! Signing this device in (`docs/PROTOCOL.md`, "Signing in a device"): the browser goes to the
//! server's `/connect-app` with a PKCE challenge, the person signs in and agrees, and the page
//! sends the browser back to a port this listens on (on 127.0.0.1 only, for a moment) with a
//! one-time code, which is swapped for the device's token.

use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand_core::{OsRng, RngCore};
use serde_json::json;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

use crate::api::{self, Api, ApiError, Device, DeviceRegistration, Me, SignedIn};
use crate::seal::DeviceKey;
use crate::store::{Credentials, Store};
use crate::system;

/// How long the browser has to come back.
pub const PATIENCE: Duration = Duration::from_secs(300);

pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
}

pub fn pkce() -> Pkce {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    let verifier = URL_SAFE_NO_PAD.encode(bytes);
    let challenge = challenge_for(&verifier);
    Pkce { verifier, challenge }
}

/// PKCE's S256: base64url(SHA-256(verifier)), 43 characters.
pub fn challenge_for(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

/// A sign-in the browser has been (or is about to be) sent off on.
pub struct Waiting {
    listener: TcpListener,
    verifier: String,
    pub server: String,
    pub device_name: String,
    /// Where the browser goes.
    pub url: String,
}

/// Starts listening and works out where to send the browser.
pub async fn start(server: &str, device_name: &str) -> Result<Waiting> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await.context("Couldn't listen for the browser coming back")?;
    let port = listener.local_addr()?.port();
    let pkce = pkce();
    let url = connect_address(server, &pkce.challenge, device_name, port)?;
    Ok(Waiting { listener, verifier: pkce.verifier, server: server.to_string(), device_name: device_name.to_string(), url })
}

pub fn connect_address(server: &str, challenge: &str, device_name: &str, port: u16) -> Result<String> {
    let mut url = url::Url::parse(&format!("{server}/connect-app")).map_err(|_| anyhow!("That isn't a server address"))?;
    url.query_pairs_mut().append_pair("challenge", challenge).append_pair("name", device_name).append_pair("redirect", &format!("http://127.0.0.1:{port}/signed-in"));
    Ok(url.into())
}

impl Waiting {
    /// Waits for the browser, swaps its code for a token, registers this device and saves the
    /// sign-in; the browser's tab is told how it went.
    pub async fn finish(self, http: &reqwest::Client, store: &Store) -> Result<Credentials> {
        let (code, mut tab) = match tokio::time::timeout(PATIENCE, self.code()).await {
            Ok(found) => found?,
            Err(_) => bail!("Nobody signed in within five minutes. Try again when you're ready."),
        };
        let result = self.swap(http, store, &code).await;
        let page = match &result {
            Ok(_) => page("You're signed in", "You can close this tab.", true),
            Err(error) => page("That didn't work", &error.to_string(), false),
        };
        let _ = tokio::time::timeout(Duration::from_secs(5), respond(&mut tab, "200 OK", &page)).await;
        result
    }

    async fn swap(&self, http: &reqwest::Client, store: &Store, code: &str) -> Result<Credentials> {
        let signed_in: SignedIn = Api::new(http, &self.server, None)
            .post("/api/auth/app/token", &json!({"code": code, "verifier": self.verifier, "deviceName": self.device_name}))
            .await?;
        let token = signed_in.token.ok_or_else(|| anyhow!("The server gave no token"))?;
        establish(http, store, &self.server, &token).await
    }

    /// The one-time code, and the browser's connection still waiting for its answer.
    async fn code(&self) -> Result<(String, TcpStream)> {
        loop {
            let (mut stream, _) = self.listener.accept().await.context("Stopped listening for the browser")?;
            let Ok(Some(target)) = tokio::time::timeout(Duration::from_secs(5), request_target(&mut stream)).await else {
                continue;
            };
            match read_target(&target) {
                Came::Code(code) => return Ok((code, stream)),
                Came::Refused(why) => {
                    let _ = respond(&mut stream, "200 OK", &page("That didn't work", &why, false)).await;
                    bail!("{why}");
                }
                Came::SomethingElse => {
                    let _ = respond(&mut stream, "404 Not Found", &page("Nothing here", "This address only finishes a sign-in.", false)).await;
                }
            }
        }
    }
}

#[derive(Debug, PartialEq)]
enum Came {
    Code(String),
    Refused(String),
    SomethingElse,
}

/// What a request for `target` ("/signed-in?code=…") means.
fn read_target(target: &str) -> Came {
    let Ok(url) = url::Url::parse(&format!("http://127.0.0.1{target}")) else {
        return Came::SomethingElse;
    };
    if url.path() != "/signed-in" {
        return Came::SomethingElse;
    }
    let find = |name: &str| url.query_pairs().find(|(k, _)| k == name).map(|(_, v)| v.to_string()).filter(|v| !v.is_empty());
    if let Some(code) = find("code").filter(|c| c.len() <= 512) {
        return Came::Code(code);
    }
    match find("error") {
        Some(error) => Came::Refused(error.chars().filter(|c| !c.is_control()).take(300).collect()),
        None => Came::SomethingElse,
    }
}

/// The path and query of a `GET`, read from the start of an HTTP request.
async fn request_target(stream: &mut TcpStream) -> Option<String> {
    let mut buffer = Vec::with_capacity(1024);
    let mut chunk = [0u8; 1024];
    while !buffer.windows(4).any(|w| w == b"\r\n\r\n") && buffer.len() < 16 * 1024 {
        let read = stream.read(&mut chunk).await.ok()?;
        if read == 0 {
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
    }
    let text = String::from_utf8_lossy(&buffer);
    let mut words = text.lines().next()?.split(' ');
    match (words.next()?, words.next()?) {
        ("GET", target) if target.starts_with('/') => Some(target.to_string()),
        _ => None,
    }
}

async fn respond(stream: &mut TcpStream, status: &str, html: &str) -> std::io::Result<()> {
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n\
         X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:\r\nConnection: close\r\n\r\n",
        html.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(html.as_bytes()).await?;
    stream.shutdown().await
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// The page the browser's tab ends on: the family's look, and nothing fetched from anywhere.
pub fn page(heading: &str, words: &str, good: bool) -> String {
    let (heading, words) = (escape(heading), escape(words));
    let glow = if good { "rgba(110,242,176,.16)" } else { "rgba(255,180,171,.14)" };
    format!(
        r##"<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>{heading} · SuperAwesomeMagical</title>
<style>
  html, body {{ height: 100%; margin: 0; }}
  body {{
    background: radial-gradient(60rem 26rem at 30% -8rem, rgba(207,189,255,.22), transparent 70%),
                radial-gradient(50rem 22rem at 78% -6rem, {glow}, transparent 70%), #0B1220;
    color: #E6EAF2; font-family: Lexend, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    display: grid; place-items: center; -webkit-font-smoothing: antialiased;
  }}
  main {{
    background: #131B2C; border: 1px solid #243047; border-radius: 28px; padding: 40px 44px; margin: 24px;
    max-width: 26rem; text-align: center; box-shadow: 0 24px 60px rgba(0,0,0,.35);
  }}
  svg {{ width: 64px; height: 64px; }}
  h1 {{ font-size: 1.6rem; font-weight: 700; margin: 18px 0 8px; letter-spacing: -.01em; }}
  p {{ margin: 0; color: #A9B4C8; font-size: 1.02rem; line-height: 1.5; }}
</style>
</head>
<body>
<main>
  <svg viewBox="0 0 128 128" role="img" aria-label="SuperAwesomeMagical">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#CFBDFF"/><stop offset="1" stop-color="#6EF2B0"/></linearGradient></defs>
    <path fill="url(#g)" d="M52 12c4 30 12 38 42 42-30 4-38 12-42 42-4-30-12-38-42-42 30-4 38-12 42-42z"/>
    <path fill="#6EF2B0" d="M98 78c2 13 5 16 18 18-13 2-16 5-18 18-2-13-5-16-18-18 13-2 16-5 18-18z"/>
  </svg>
  <h1>{heading}</h1>
  <p>{words}</p>
</main>
</body>
</html>
"##
    )
}

/// With a token in hand: makes this device's key if there isn't one, registers the device, and
/// saves the sign-in and the server's address.
pub async fn establish(http: &reqwest::Client, store: &Store, server: &str, token: &str) -> Result<Credentials> {
    let api = Api::new(http, server, Some(token));
    let me: Me = api.get("/api/me").await.map_err(|e| match e {
        ApiError::Unauthorized(_) => anyhow!("The server doesn't know that token"),
        other => anyhow!(other),
    })?;
    let mut credentials = store.credentials();
    let key = match credentials.private_key.as_deref().map(DeviceKey::from_private_text) {
        Some(Ok(key)) => key,
        _ => DeviceKey::generate(),
    };
    let device = register(&api, &system::device_name(), &key).await?;
    credentials = Credentials {
        token: Some(token.to_string()),
        private_key: Some(key.private_text()),
        device_id: device.id,
        device_name: device.name,
        username: me.user.username,
        display_name: me.user.display_name,
    };
    // The sign-in first: an address without it is harmless, the other way round is not.
    store.save_credentials(&credentials)?;
    let mut config = store.config();
    config.server = server.to_string();
    store.save_config(&config)?;
    Ok(credentials)
}

/// Tells the server what this device is and gives it the key things for it are sealed with.
pub async fn register(api: &Api, name: &str, key: &DeviceKey) -> Result<Device, ApiError> {
    let model = system::model();
    api.post("/api/devices", &DeviceRegistration { name, kind: "LINUX", model: &model, public_key: &key.public_text(), capabilities: api::CAPABILITIES }).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pkce_is_s256() {
        // RFC 7636, appendix B.
        assert_eq!(challenge_for("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        let made = pkce();
        assert_eq!(made.verifier.len(), 43);
        assert_eq!(made.challenge.len(), 43);
        assert!(made.challenge.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'), "what the server's check allows");
        assert_ne!(pkce().verifier, made.verifier);
    }

    #[test]
    fn the_browser_is_sent_to_connect_app() {
        let url = connect_address("https://o.example.com", "abc", "Maddie's laptop & co", 40123).unwrap();
        assert_eq!(url, "https://o.example.com/connect-app?challenge=abc&name=Maddie%27s+laptop+%26+co&redirect=http%3A%2F%2F127.0.0.1%3A40123%2Fsigned-in");
        assert!(connect_address("http://192.168.1.5:8080/samo", "abc", "x", 1).unwrap().starts_with("http://192.168.1.5:8080/samo/connect-app?"));
    }

    #[test]
    fn only_the_sign_in_coming_back_counts() {
        assert_eq!(read_target("/signed-in?code=abc123"), Came::Code("abc123".into()));
        assert_eq!(read_target("/signed-in?x=1&code=a%2Bb"), Came::Code("a+b".into()));
        assert_eq!(read_target("/signed-in?error=You+said+no"), Came::Refused("You said no".into()));
        for other in ["/", "/favicon.ico", "/signed-in", "/signed-in?code=", "/signed-in/x?code=abc", "/other?code=abc", "/signed-inx?code=abc"] {
            assert_eq!(read_target(other), Came::SomethingElse, "{other}");
        }
        assert_eq!(read_target(&format!("/signed-in?code={}", "a".repeat(600))), Came::SomethingElse);
    }

    #[test]
    fn the_page_stands_alone() {
        let html = page("You're signed in", "You can close this tab.", true);
        assert!(html.contains("You're signed in") && html.contains("You can close this tab."));
        assert!(html.contains("#0B1220") && html.contains("Lexend"));
        // Nothing fetched from anywhere: no links out, scripts, or addresses at all.
        for outside in ["http://", "https://", "//", "<script", "<link", "@import", "url(\"", "url(h", "src="] {
            assert!(!html.replace("<!doctype html>", "").contains(outside), "{outside}");
        }
        let hostile = page("That didn't work", "<script>alert(1)</script> & \"more\"", false);
        assert!(!hostile.contains("<script>") && hostile.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;more&quot;"));
    }

    #[tokio::test]
    async fn the_listener_takes_the_code_and_ignores_the_rest() {
        let waiting = start("https://o.example.com", "Test").await.unwrap();
        let port = waiting.listener.local_addr().unwrap().port();
        assert!(waiting.url.contains(&format!("redirect=http%3A%2F%2F127.0.0.1%3A{port}%2Fsigned-in")));
        let browser = tokio::spawn(async move {
            let ask = |request: String| async move {
                let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
                stream.write_all(request.as_bytes()).await.unwrap();
                stream
            };
            // A favicon request and a POST first: neither ends the wait.
            let mut answer = String::new();
            ask("GET /favicon.ico HTTP/1.1\r\nHost: x\r\n\r\n".into()).await.read_to_string(&mut answer).await.unwrap();
            assert!(answer.starts_with("HTTP/1.1 404"), "{answer}");
            let mut refused = String::new();
            let _ = ask("POST /signed-in?code=nope HTTP/1.1\r\nHost: x\r\n\r\n".into()).await.read_to_string(&mut refused).await;
            assert!(refused.is_empty(), "a POST gets no answer");
            ask("GET /signed-in?code=one-time HTTP/1.1\r\nHost: x\r\n\r\n".into()).await
        });
        let (code, mut tab) = tokio::time::timeout(Duration::from_secs(10), waiting.code()).await.unwrap().unwrap();
        assert_eq!(code, "one-time");
        respond(&mut tab, "200 OK", &page("You're signed in", "You can close this tab.", true)).await.unwrap();
        let mut answer = String::new();
        browser.await.unwrap().read_to_string(&mut answer).await.unwrap();
        assert!(answer.starts_with("HTTP/1.1 200 OK\r\n") && answer.contains("default-src 'none'") && answer.contains("You're signed in"));
    }
}
