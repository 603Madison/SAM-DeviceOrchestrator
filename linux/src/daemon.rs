//! The agent itself: `app.superawesomemagical.Orchestrator` on the session bus
//! (`docs/PROTOCOL.md`, "On the device"), the live line to the server, and everything the
//! person's other devices send here or are sent from here.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use tokio::sync::{Notify, mpsc, watch};
use tokio::task::AbortHandle;
use tokio_tungstenite::tungstenite::Message as Frame;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use zbus::object_server::SignalEmitter;
use zbus::zvariant::{OwnedValue, Value as Variant};

use crate::api::{self, Api, ApiError, Battery, Device, IncomingMessage, Payload, SentMessage, StreamFrame, Ticket};
use crate::links::{self, Checked};
use crate::monitor::{self, Event, NOTIFICATIONS};
use crate::notes::{self, Coalesce, MIRRORED_HINT, OWN_DESKTOP_ENTRY, Offer, Outgoing};
use crate::seal::{self, DeviceKey};
use crate::signin;
use crate::store::{Config, Credentials, Store};
use crate::system;

pub const NAME: &str = "app.superawesomemagical.Orchestrator";
pub const PATH: &str = "/app/superawesomemagical/Orchestrator";
pub const INTERFACE: &str = "app.superawesomemagical.Orchestrator1";

const NOTIFICATIONS_PATH: &str = "/org/freedesktop/Notifications";
/// What our own notifications are from.
const OWN_APP_NAME: &str = "SuperAwesomeMagical";
/// How long another device's activity is kept without news of it.
const ACTIVITY_LIFE: Duration = Duration::from_secs(15 * 60);
/// The same activity published again within this is dropped.
const ACTIVITY_REPEAT: Duration = Duration::from_secs(5);
const RING_FOR: Duration = Duration::from_secs(60);
/// The most a payload may be before sealing (the server takes about 48 KB sealed).
const MAX_PAYLOAD: usize = 45_000;
const MAX_REMEMBERED: usize = 500;

macro_rules! log {
    ($($arg:tt)*) => { eprintln!("sam-orchestrator: {}", format!($($arg)*)) };
}

/// The errors `docs/PROTOCOL.md` names.
#[derive(Debug, zbus::DBusError)]
#[zbus(prefix = "app.superawesomemagical.Orchestrator.Error")]
pub enum Error {
    #[zbus(error)]
    ZBus(zbus::Error),
    NotSignedIn(String),
    UnknownApp(String),
    Failed(String),
}

fn not_signed_in() -> Error {
    Error::NotSignedIn("Nobody's signed in on this computer".into())
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// A name from elsewhere, safe to show on one line.
fn tidy(text: &str, most: usize) -> String {
    text.chars()
        .filter(|c| !c.is_control())
        .take(most)
        .collect::<String>()
        .trim()
        .to_string()
}

struct Activity {
    device_name: String,
    title: String,
    detail: String,
    url: String,
    at: i64,
    heard: Instant,
}

struct Ring {
    task: AbortHandle,
    notification: u32,
}

#[derive(Default)]
struct State {
    config: Config,
    credentials: Credentials,
    key: Option<DeviceKey>,
    connected: bool,
    devices: Vec<Device>,
    /// Where the browser was sent, while a sign-in is under way.
    signing_in: Option<String>,
    /// The pairing code the sign-in page shows too.
    pairing_code: String,
    sign_in_task: Option<AbortHandle>,
    /// Why the last sign-in didn't work.
    error: String,
    /// Messages already dealt with (the server repeats what wasn't acknowledged).
    handled: VecDeque<String>,
    /// Other devices' notifications showing here: (device, key) to the id here, and back.
    shown: HashMap<(String, String), u32>,
    shown_ids: HashMap<u32, (String, String)>,
    /// Notify calls seen, waiting for the id in their answer.
    asked: HashMap<(String, u32), (Instant, Outgoing)>,
    /// This computer's notifications sent to the other devices, by their id here.
    sent: Coalesce<Payload>,
    activities: HashMap<(String, String), Activity>,
    published: HashMap<String, (Instant, String)>,
    ring: Option<Ring>,
    markup: Option<(Instant, bool)>,
}

pub struct Agent {
    connection: OnceLock<zbus::Connection>,
    http: reqwest::Client,
    store: Store,
    state: Mutex<State>,
    /// Wakes the line when someone signs in.
    wake: Notify,
    /// Changes whenever the sign-in does: a line opened for the old one closes.
    epoch: watch::Sender<u64>,
    /// Frames for the open line (acknowledgements, battery).
    line: Mutex<Option<mpsc::UnboundedSender<String>>>,
}

enum LineEnd {
    /// The server no longer knows the token.
    SignedOut,
    /// It was open and closed, or the sign-in changed.
    Closed,
    /// It never opened.
    Failed,
}

impl Agent {
    pub fn new(store: Store) -> Arc<Self> {
        let agent = Arc::new(Self {
            connection: OnceLock::new(),
            http: api::http_client(),
            store,
            state: Mutex::new(State::default()),
            wake: Notify::new(),
            epoch: watch::channel(0).0,
            line: Mutex::new(None),
        });
        agent.load();
        agent
    }

    fn state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Reads the settings and the sign-in from disk (at start, and when something else changed them).
    fn load(&self) {
        let config = self.store.config();
        let credentials = self.store.credentials();
        let key = credentials
            .private_key
            .as_deref()
            .and_then(|k| DeviceKey::from_private_text(k).ok());
        let mut state = self.state();
        let changed = state.credentials.token != credentials.token || state.config.server != config.server;
        state.config = config;
        state.credentials = credentials;
        state.key = key;
        if changed {
            state.devices.clear();
            state.connected = false;
            state.activities.clear();
            state.sent.clear();
            drop(state);
            self.epoch.send_modify(|e| *e += 1);
            self.wake.notify_one();
        }
    }

    async fn emit(&self, signal: &str) {
        if let Some(connection) = self.connection.get() {
            let _ = connection.emit_signal(None::<&str>, PATH, INTERFACE, signal, &()).await;
        }
    }

    fn connection(&self) -> Result<&zbus::Connection, String> {
        self.connection.get().ok_or_else(|| "Not on the session bus".to_string())
    }

    // ---- Who's signed in ----

    pub fn status(&self) -> Value {
        let state = self.state();
        let signed_in = state.credentials.signed_in();
        let mut status = json!({
            "signedIn": signed_in,
            "server": state.config.server,
            "username": state.credentials.username,
            "displayName": state.credentials.display_name,
            "connected": signed_in && state.connected,
            "device": {
                "id": state.credentials.device_id,
                "name": if state.credentials.device_name.is_empty() { system::device_name() } else { state.credentials.device_name.clone() },
            },
            "signingIn": state.signing_in.is_some(),
        });
        if let Some(url) = &state.signing_in {
            status["signInUrl"] = json!(url);
            status["pairingCode"] = json!(state.pairing_code);
        }
        if !state.error.is_empty() {
            status["error"] = json!(state.error);
        }
        status
    }

    /// The server as this device, when someone's signed in.
    fn api(&self) -> Result<(Api, String), Error> {
        let state = self.state();
        match &state.credentials.token {
            Some(token) if !token.is_empty() && !state.config.server.is_empty() => Ok((
                Api::new(&self.http, &state.config.server, Some(token)),
                state.credentials.device_id.clone(),
            )),
            _ => Err(not_signed_in()),
        }
    }

    /// What a refusal from the server means for whoever asked; a 401 signs this device out.
    async fn refused(&self, api: &Api, error: ApiError) -> Error {
        match error {
            ApiError::Unauthorized(_) => {
                self.signed_out(api).await;
                Error::NotSignedIn("This computer was signed out".into())
            }
            other => Error::Failed(other.to_string()),
        }
    }

    /// The server said the token is no good: forget it, and say so. (Unless the sign-in has
    /// changed since the question was asked.)
    async fn signed_out(&self, asked_as: &Api) {
        {
            let mut state = self.state();
            let same = state.credentials.token.as_deref().is_some_and(|t| asked_as.is_token(t));
            if !same {
                return;
            }
            state.credentials.forget();
            state.connected = false;
            state.devices.clear();
            state.activities.clear();
            state.sent.clear();
            if let Err(error) = self.store.save_credentials(&state.credentials) {
                log!("couldn't save the sign-out: {error:#}");
            }
        }
        log!("signed out by the server");
        self.epoch.send_modify(|e| *e += 1);
        self.emit("Changed").await;
        self.emit("ActivitiesChanged").await;
    }

    pub async fn sign_in(self: &Arc<Self>, server: &str) -> Result<(), Error> {
        let server = api::server_address(server).map_err(Error::Failed)?;
        if api::is_plain_http(&server) {
            log!("signing in over http://: anyone on the network between here and the server can read the sign-in");
        }
        let waiting = signin::start(&server, &system::device_name())
            .await
            .map_err(|e| Error::Failed(format!("{e:#}")))?;
        let (url, code) = (waiting.url.clone(), waiting.pairing_code.clone());
        let before = self.api().ok();
        let agent = self.clone();
        let task = tokio::spawn(async move {
            // A moment for whoever asked to show the pairing code before the page appears.
            tokio::time::sleep(Duration::from_millis(800)).await;
            if let Err(why) = system::open(&waiting.url).await {
                log!("couldn't open the browser ({why}); the address to open is in Status");
            }
            let result = waiting.finish(&agent.http, &agent.store).await;
            {
                let mut state = agent.state();
                state.signing_in = None;
                state.pairing_code.clear();
                state.sign_in_task = None;
                state.error = result.as_ref().err().map(|e| format!("{e:#}")).unwrap_or_default();
            }
            match result {
                Ok(_) => {
                    log!("signed in");
                    agent.load();
                    // The sign-in this one replaces: its device entry goes with it.
                    if let Some((old, device)) = before.filter(|(_, device)| !device.is_empty()) {
                        let _ = old.delete(&format!("/api/devices/{device}")).await;
                    }
                }
                Err(error) => log!("sign-in didn't finish: {error:#}"),
            }
            agent.emit("Changed").await;
        });
        {
            let mut state = self.state();
            if let Some(earlier) = state.sign_in_task.replace(task.abort_handle()) {
                earlier.abort();
            }
            state.signing_in = Some(url);
            state.pairing_code = code;
            state.error.clear();
        }
        self.emit("Changed").await;
        Ok(())
    }

    pub async fn sign_out(&self) -> Result<(), Error> {
        let before = self.api().ok();
        {
            let mut state = self.state();
            if let Some(task) = state.sign_in_task.take() {
                task.abort();
            }
            state.signing_in = None;
            state.pairing_code.clear();
            state.error.clear();
            state.credentials.forget();
            state.connected = false;
            state.devices.clear();
            state.activities.clear();
            state.sent.clear();
            self.store
                .save_credentials(&state.credentials)
                .map_err(|e| Error::Failed(format!("{e:#}")))?;
        }
        self.epoch.send_modify(|e| *e += 1);
        // Removing the device signs it out on the server too, and takes it off the other devices' lists.
        if let Some((api, device)) = before {
            let gone = if device.is_empty() {
                api.post::<Value, Value>("/api/auth/logout", &json!({})).await
            } else {
                api.delete(&format!("/api/devices/{device}")).await
            };
            if let Err(error) = gone {
                log!("signed out here, but couldn't tell the server: {error}");
            }
        }
        self.emit("Changed").await;
        self.emit("ActivitiesChanged").await;
        Ok(())
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<(), Error> {
        let mut state = self.state();
        // Start from what's on disk, so a change made by hand isn't lost.
        let mut config = self.store.config();
        config.server = state.config.server.clone();
        config.set(key, value).map_err(Error::Failed)?;
        self.store.save_config(&config).map_err(|e| Error::Failed(format!("{e:#}")))?;
        state.config = config;
        Ok(())
    }

    // ---- The server ----

    async fn refresh_devices(&self) -> Result<Vec<Device>, Error> {
        let (api, _) = self.api()?;
        match api.get::<Vec<Device>>("/api/devices").await {
            Ok(devices) => {
                self.state().devices = devices.clone();
                Ok(devices)
            }
            Err(error) => Err(self.refused(&api, error).await),
        }
    }

    pub async fn devices(&self) -> Result<Vec<Device>, Error> {
        self.api()?;
        let known = {
            let state = self.state();
            (state.connected && !state.devices.is_empty()).then(|| state.devices.clone())
        };
        match known {
            Some(devices) => Ok(devices),
            None => self.refresh_devices().await,
        }
    }

    pub async fn apps(&self) -> Result<Value, Error> {
        let (api, _) = self.api()?;
        match api.get::<Value>("/api/apps").await {
            Ok(apps) => Ok(apps),
            Err(error) => Err(self.refused(&api, error).await),
        }
    }

    pub async fn ticket(&self, app: &str) -> Result<(String, String), Error> {
        let (api, _) = self.api()?;
        match api.post::<_, Ticket>("/api/tickets", &json!({"app": app})).await {
            Ok(ticket) => Ok((ticket.url, ticket.ticket)),
            Err(ApiError::Status(404, words)) => Err(Error::UnknownApp(words)),
            Err(error) => Err(self.refused(&api, error).await),
        }
    }

    /// Seals `payload` for one device, or (`""`) for every other device that takes its kind.
    pub async fn send(&self, target: &str, payload: &Value) -> Result<SentMessage, Error> {
        let (api, me) = self.api()?;
        let delivery = api::delivery(payload);
        let plain = serde_json::to_vec(payload).map_err(|_| Error::Failed("That can't be sent".into()))?;
        if plain.len() > MAX_PAYLOAD {
            return Err(Error::Failed("That's too big to send".into()));
        }
        if !target.is_empty() && target == me {
            return Err(Error::Failed("That's this computer".into()));
        }
        let mut devices = self.state().devices.clone();
        if devices.is_empty() || (!target.is_empty() && !devices.iter().any(|d| d.id == target)) {
            devices = self.refresh_devices().await?;
        }
        let mut to = serde_json::Map::new();
        if target.is_empty() {
            for device in devices.iter().filter(|d| d.id != me && !d.current) {
                if delivery
                    .capability
                    .is_some_and(|c| !device.capabilities.iter().any(|have| have == c))
                {
                    continue;
                }
                match seal::seal(&plain, &device.public_key, &device.id) {
                    Ok(sealed) => {
                        to.insert(device.id.clone(), json!(sealed));
                    }
                    Err(_) => log!("skipping a device whose key isn't one"),
                }
            }
        } else {
            let device = devices
                .iter()
                .find(|d| d.id == target)
                .ok_or_else(|| Error::Failed("No such device".into()))?;
            let sealed = seal::seal(&plain, &device.public_key, &device.id)
                .map_err(|_| Error::Failed(format!("{}'s key isn't one this can seal for", device.name)))?;
            to.insert(device.id.clone(), json!(sealed));
        }
        if to.is_empty() {
            return Ok(SentMessage::default());
        }
        let mut body = json!({"to": to, "ttlSeconds": delivery.ttl_seconds});
        if let Some(key) = delivery.collapse_key {
            body["collapseKey"] = json!(key);
        }
        match api.post::<_, SentMessage>("/api/messages", &body).await {
            Ok(sent) => Ok(sent),
            Err(error) => Err(self.refused(&api, error).await),
        }
    }

    /// Sends to the other devices without anyone waiting on how it went.
    async fn tell_others(&self, payload: Payload) {
        let kind = payload.to_json();
        let value: Value = serde_json::from_str(&kind).expect("payloads are JSON");
        if let Err(error) = self.send("", &value).await
            && !matches!(error, Error::NotSignedIn(_))
        {
            log!(
                "couldn't send a {} to the other devices: {error}",
                value["type"].as_str().unwrap_or("message")
            );
        }
    }

    pub async fn publish_activity(&self, app: &str, title: &str, detail: &str, url: &str) -> Result<(), Error> {
        let app = app.trim().to_lowercase();
        if app.is_empty() || app.len() > 48 || !app.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-')) {
            return Err(Error::Failed("An app's id is a short name like music or video".into()));
        }
        self.api()?;
        let title = tidy(title, 300);
        let (detail, url) = (tidy(detail, 300), tidy(url, 2000));
        let same = format!("{title}\n{detail}\n{url}");
        {
            let mut state = self.state();
            let now = Instant::now();
            if state
                .published
                .get(&app)
                .is_some_and(|(at, what)| *what == same && now.duration_since(*at) < ACTIVITY_REPEAT)
            {
                return Ok(());
            }
            state.published.insert(app.clone(), (now, same));
        }
        let payload = Payload::Activity {
            app,
            title: (!title.is_empty()).then_some(title),
            detail,
            url,
            at: now_ms(),
        };
        self.send("", &serde_json::to_value(payload).expect("payloads are JSON"))
            .await
            .map(|_| ())
    }

    pub fn activities(&self) -> Value {
        let state = self.state();
        let mut list: Vec<(&(String, String), &Activity)> =
            state.activities.iter().filter(|(_, a)| a.heard.elapsed() < ACTIVITY_LIFE).collect();
        list.sort_by_key(|(_, a)| std::cmp::Reverse(a.at));
        list.into_iter()
            .map(|((device, app), a)| {
                let name = state
                    .devices
                    .iter()
                    .find(|d| d.id == *device)
                    .map(|d| d.name.clone())
                    .unwrap_or_else(|| a.device_name.clone());
                json!({"device": device, "deviceName": name, "app": app, "title": a.title, "detail": a.detail, "url": a.url, "at": a.at})
            })
            .collect()
    }

    // ---- The live line ----

    async fn line_loop(self: Arc<Self>) {
        let mut pause = Duration::from_secs(1);
        let mut epoch = self.epoch.subscribe();
        loop {
            let session = {
                let state = self.state();
                state
                    .credentials
                    .token
                    .clone()
                    .filter(|t| !t.is_empty() && !state.config.server.is_empty())
                    .map(|t| (state.config.server.clone(), t))
            };
            let Some((server, token)) = session else {
                self.wake.notified().await;
                pause = Duration::from_secs(1);
                continue;
            };
            epoch.borrow_and_update();
            let api = Api::new(&self.http, &server, Some(&token));
            let end = tokio::select! {
                end = self.run_line(&api, &server, &token) => end,
                _ = epoch.changed() => LineEnd::Closed,
            };
            *self.line.lock().unwrap_or_else(|p| p.into_inner()) = None;
            let was_connected = std::mem::replace(&mut self.state().connected, false);
            match end {
                LineEnd::SignedOut => {
                    self.signed_out(&api).await;
                    pause = Duration::from_secs(1);
                    continue;
                }
                LineEnd::Closed => pause = Duration::from_secs(1),
                LineEnd::Failed => {}
            }
            if was_connected {
                self.emit("Changed").await;
            }
            // A growing pause, 1 s to 5 min; a new sign-in doesn't wait it out.
            tokio::select! {
                _ = tokio::time::sleep(pause) => {}
                _ = epoch.changed() => pause = Duration::from_secs(1),
            }
            pause = (pause * 2).min(Duration::from_secs(300));
        }
    }

    async fn run_line(self: &Arc<Self>, api: &Api, server: &str, token: &str) -> LineEnd {
        // Is this device still known? (The line itself can't say why it was refused.)
        match api.get::<Device>("/api/devices/me").await {
            Ok(device) => {
                let key = self.state().key.clone();
                let ours = key.as_ref().map(DeviceKey::public_text);
                if ours.as_deref() != Some(device.public_key.as_str()) {
                    // The key changed (or was lost): the server needs the one things can be opened with.
                    let key = key.unwrap_or_else(DeviceKey::generate);
                    match signin::register(api, &device.name, &key).await {
                        Ok(device) => self.registered(device, key),
                        Err(ApiError::Unauthorized(_)) => return LineEnd::SignedOut,
                        Err(error) => {
                            log!("couldn't register this device: {error}");
                            return LineEnd::Failed;
                        }
                    }
                } else {
                    let key = key.expect("a key that matched");
                    self.registered(device, key);
                }
            }
            Err(ApiError::Unauthorized(_)) => return LineEnd::SignedOut,
            Err(ApiError::Status(403, _)) => {
                let key = self.state().key.clone().unwrap_or_else(DeviceKey::generate);
                match signin::register(api, &system::device_name(), &key).await {
                    Ok(device) => self.registered(device, key),
                    Err(ApiError::Unauthorized(_)) => return LineEnd::SignedOut,
                    Err(error) => {
                        log!("couldn't register this device: {error}");
                        return LineEnd::Failed;
                    }
                }
            }
            Err(error) => {
                log!("{error}");
                return LineEnd::Failed;
            }
        }

        let Ok(mut request) = api::stream_address(server).into_client_request() else {
            return LineEnd::Failed;
        };
        let Ok(bearer) = format!("Bearer {token}").parse() else {
            return LineEnd::SignedOut;
        };
        request.headers_mut().insert("Authorization", bearer);
        let socket = match tokio::time::timeout(Duration::from_secs(30), tokio_tungstenite::connect_async(request)).await {
            Ok(Ok((socket, _))) => socket,
            Ok(Err(tokio_tungstenite::tungstenite::Error::Http(response))) if response.status() == 401 => return LineEnd::SignedOut,
            Ok(Err(error)) => {
                log!("couldn't open the line to the server: {error}");
                return LineEnd::Failed;
            }
            Err(_) => {
                log!("couldn't open the line to the server: it took too long");
                return LineEnd::Failed;
            }
        };
        let (mut write, mut read) = socket.split();
        let (out, mut outgoing) = mpsc::unbounded_channel::<String>();
        *self.line.lock().unwrap_or_else(|p| p.into_inner()) = Some(out.clone());
        self.state().connected = true;
        log!("connected");
        self.emit("Changed").await;

        let mut battery: Option<Battery> = None;
        let mut battery_check = tokio::time::interval(Duration::from_secs(60));
        let mut ping = tokio::time::interval_at(tokio::time::Instant::now() + Duration::from_secs(30), Duration::from_secs(30));
        let mut heard = Instant::now();
        loop {
            tokio::select! {
                frame = read.next() => {
                    let text = match frame {
                        Some(Ok(Frame::Text(text))) => text,
                        Some(Ok(Frame::Close(why))) => {
                            log!("the server closed the line{}", why.map(|w| format!(": {}", w.reason)).unwrap_or_default());
                            return LineEnd::Closed;
                        }
                        None => {
                            log!("the line to the server ended");
                            return LineEnd::Closed;
                        }
                        Some(Ok(_)) => { heard = Instant::now(); continue }
                        Some(Err(error)) => {
                            log!("the line to the server dropped: {error}");
                            return LineEnd::Closed;
                        }
                    };
                    heard = Instant::now();
                    match serde_json::from_str::<StreamFrame>(text.as_str()) {
                        Ok(StreamFrame::Message { message }) => {
                            // Dealt with in turn, then acknowledged: until then the server keeps it.
                            let id = message.id.clone();
                            self.incoming(message).await;
                            let _ = out.send(json!({"type": "ack", "ids": [id]}).to_string());
                        }
                        Ok(StreamFrame::Devices { devices }) => {
                            {
                                let mut state = self.state();
                                // Renamed from another device or the web page: that's this computer's name now.
                                if let Some(me) = devices.iter().find(|d| d.current && d.id == state.credentials.device_id)
                                    && me.name != state.credentials.device_name
                                {
                                    state.credentials.device_name = me.name.clone();
                                    if let Err(error) = self.store.save_credentials(&state.credentials) {
                                        log!("couldn't save this device's new name: {error:#}");
                                    }
                                }
                                state.devices = devices;
                            }
                            self.emit("Changed").await;
                        }
                        Ok(StreamFrame::Ping | StreamFrame::Unknown) | Err(_) => {}
                    }
                }
                Some(frame) = outgoing.recv() => {
                    if write.send(Frame::text(frame)).await.is_err() {
                        log!("the line to the server dropped while sending");
                        return LineEnd::Closed;
                    }
                }
                _ = battery_check.tick() => {
                    // On connecting, and whenever it moves a percent or the charger goes in or out.
                    let now = system::battery();
                    if now.is_some() && now != battery {
                        battery = now;
                        let _ = out.send(json!({"type": "status", "battery": now}).to_string());
                    }
                }
                _ = ping.tick() => {
                    if heard.elapsed() > Duration::from_secs(100) {
                        log!("the line to the server went quiet");
                        return LineEnd::Closed;
                    }
                    let _ = out.send(json!({"type": "ping"}).to_string());
                }
            }
        }
    }

    /// The server's record of this device, kept (and the key it was registered with).
    fn registered(&self, device: Device, key: DeviceKey) {
        let mut state = self.state();
        let private = Some(key.private_text());
        state.key = Some(key);
        if state.credentials.device_id != device.id
            || state.credentials.device_name != device.name
            || state.credentials.private_key != private
        {
            state.credentials.device_id = device.id;
            state.credentials.device_name = device.name;
            state.credentials.private_key = private;
            if let Err(error) = self.store.save_credentials(&state.credentials) {
                log!("couldn't save this device's details: {error:#}");
            }
        }
    }

    // ---- What the other devices send ----

    async fn incoming(self: &Arc<Self>, message: IncomingMessage) {
        let (key, me) = {
            let state = self.state();
            if state.handled.contains(&message.id) {
                return;
            }
            (state.key.clone(), state.credentials.device_id.clone())
        };
        let from = message.from.clone().unwrap_or_default();
        let from_name = {
            let state = self.state();
            let known = state
                .devices
                .iter()
                .find(|d| !from.is_empty() && d.id == from)
                .map(|d| d.name.clone());
            let name = tidy(&known.unwrap_or_else(|| message.from_name.clone()), 64);
            if name.is_empty() { "Another device".to_string() } else { name }
        };
        match key.map(|key| seal::open(&message.sealed, &key, &me)) {
            Some(Ok(plain)) => match api::parse_payload(&plain) {
                Ok(Some(payload)) => self.act(payload, &from, &from_name).await,
                Ok(None) => log!("skipped a kind of message this version doesn't know"),
                Err(why) => log!("skipped a message that isn't one ({why})"),
            },
            _ => log!("couldn't open a message from {from_name}: it wasn't sealed for this device's key"),
        }
        let mut state = self.state();
        state.handled.push_back(message.id);
        while state.handled.len() > MAX_REMEMBERED {
            state.handled.pop_front();
        }
    }

    async fn act(self: &Arc<Self>, payload: Payload, from: &str, from_name: &str) {
        let config = self.state().config.clone();
        match payload {
            Payload::Notification { key, app, title, text, .. } => {
                if config.notifications_in {
                    self.show(from, from_name, &key, &app, &title, &text).await;
                }
            }
            Payload::Dismiss { key } => self.dismissed_elsewhere(from, &key).await,
            Payload::Clipboard { text } => {
                if !config.clipboard {
                    return;
                }
                if text.len() > system::MAX_CLIPBOARD {
                    log!("didn't take a clipboard from {from_name}: too big");
                } else if let Err(why) = system::set_clipboard(&text).await {
                    log!("couldn't take the clipboard from {from_name}: {why}");
                }
            }
            Payload::Link { url, title } => {
                if config.links {
                    self.open_link(&url, &title, from_name).await;
                }
            }
            Payload::Ring { on: true } => self.ring(from_name).await,
            Payload::Ring { on: false } => self.stop_ringing().await,
            Payload::Activity {
                app,
                title,
                detail,
                url,
                at,
            } => {
                let app = tidy(&app, 48);
                {
                    let mut state = self.state();
                    let key = (from.to_string(), app);
                    match title.map(|t| tidy(&t, 300)).filter(|t| !t.is_empty()) {
                        Some(title) => {
                            let activity = Activity {
                                device_name: from_name.to_string(),
                                title,
                                detail: tidy(&detail, 300),
                                url: tidy(&url, 2000),
                                at,
                                heard: Instant::now(),
                            };
                            state.activities.insert(key, activity);
                        }
                        None => {
                            state.activities.remove(&key);
                        }
                    }
                }
                self.emit("ActivitiesChanged").await;
            }
            Payload::Media { action } => {
                if let Err(why) = self.media(&action).await {
                    log!("couldn't {action} what's playing: {why}");
                }
            }
        }
    }

    async fn open_link(&self, url: &str, title: &str, from_name: &str) {
        let heading = format!("Link from {from_name}");
        let checked = match links::check(url) {
            Ok(checked) => checked,
            Err(why) => {
                log!("didn't open a link from {from_name}: {why}");
                self.say(&heading, &format!("Not opened: {why}.")).await;
                return;
            }
        };
        if let Checked::Family(scheme, _) = &checked
            && !system::handles_scheme(scheme).await
        {
            self.say(&heading, &format!("Not opened: no app here opens {scheme} links yet."))
                .await;
            return;
        }
        let address = checked.address();
        let title = tidy(title, 200);
        match system::open(address).await {
            Ok(()) => {
                let words = if title.is_empty() {
                    format!("Opened {address}")
                } else {
                    format!("Opened {title}\n{address}")
                };
                self.say(&heading, &words).await;
            }
            Err(why) => {
                log!("couldn't open a link from {from_name}: {why}");
                self.say(&heading, &format!("Couldn't open it: {why}.\n{address}")).await;
            }
        }
    }

    // ---- Notifications ----

    #[allow(clippy::too_many_arguments)]
    async fn notify(
        &self,
        app_name: &str,
        replaces: u32,
        summary: &str,
        body: &str,
        actions: &[&str],
        hints: HashMap<&str, Variant<'_>>,
        timeout: i32,
    ) -> Result<u32, String> {
        let connection = self.connection()?;
        let arguments = (app_name, replaces, "", summary, body, actions, hints, timeout);
        let call = connection.call_method(Some(NOTIFICATIONS), NOTIFICATIONS_PATH, Some(NOTIFICATIONS), "Notify", &arguments);
        match tokio::time::timeout(Duration::from_secs(10), call).await {
            Ok(Ok(reply)) => reply.body().deserialize::<u32>().map_err(|e| e.to_string()),
            Ok(Err(error)) => Err(error.to_string()),
            Err(_) => Err("the notification service didn't answer".into()),
        }
    }

    async fn close_notification(&self, id: u32) {
        if let Ok(connection) = self.connection() {
            let arguments = (id,);
            let call = connection.call_method(
                Some(NOTIFICATIONS),
                NOTIFICATIONS_PATH,
                Some(NOTIFICATIONS),
                "CloseNotification",
                &arguments,
            );
            let _ = tokio::time::timeout(Duration::from_secs(10), call).await;
        }
    }

    /// Whether the notification daemon reads markup in bodies (then plain words need escaping).
    async fn reads_markup(&self) -> bool {
        let known = self.state().markup;
        if let Some((at, markup)) = known
            && at.elapsed() < Duration::from_secs(60)
        {
            return markup;
        }
        let Ok(connection) = self.connection() else { return false };
        let call = connection.call_method(Some(NOTIFICATIONS), NOTIFICATIONS_PATH, Some(NOTIFICATIONS), "GetCapabilities", &());
        let markup = match tokio::time::timeout(Duration::from_secs(5), call).await {
            Ok(Ok(reply)) => reply
                .body()
                .deserialize::<Vec<String>>()
                .map(|c| c.iter().any(|c| c == "body-markup"))
                .unwrap_or(false),
            _ => false,
        };
        self.state().markup = Some((Instant::now(), markup));
        markup
    }

    async fn body(&self, words: &str) -> String {
        if self.reads_markup().await {
            notes::escape_markup(words)
        } else {
            words.to_string()
        }
    }

    /// A notification of the agent's own.
    async fn say(&self, summary: &str, words: &str) {
        let hints = HashMap::from([("desktop-entry", Variant::from(OWN_DESKTOP_ENTRY))]);
        if let Err(why) = self.notify(OWN_APP_NAME, 0, summary, &self.body(words).await, &[], hints, -1).await {
            log!("couldn't show a notification: {why}");
        }
    }

    /// Shows another device's notification here, marked with that device's name.
    async fn show(&self, from: &str, from_name: &str, key: &str, app: &str, title: &str, text: &str) {
        let app = tidy(app, 100);
        let app_name = if app.is_empty() {
            from_name.to_string()
        } else {
            format!("{app} · {from_name}")
        };
        let title = tidy(title, 300);
        let summary = if title.is_empty() { app_name.clone() } else { title };
        let text: String = text.chars().filter(|c| *c == '\n' || !c.is_control()).take(4000).collect();
        let slot = (from.to_string(), key.to_string());
        // The same key again (an update, or the server repeating itself) replaces what's showing.
        let replaces = self.state().shown.get(&slot).copied().unwrap_or(0);
        let hints = HashMap::from([
            (MIRRORED_HINT, Variant::from(true)),
            ("desktop-entry", Variant::from(OWN_DESKTOP_ENTRY)),
        ]);
        match self
            .notify(&app_name, replaces, &summary, &self.body(&text).await, &[], hints, -1)
            .await
        {
            Ok(id) => {
                let mut state = self.state();
                if replaces != 0 && replaces != id {
                    state.shown_ids.remove(&replaces);
                }
                if state.shown.len() >= MAX_REMEMBERED {
                    state.shown.clear();
                    state.shown_ids.clear();
                }
                state.shown.insert(slot.clone(), id);
                state.shown_ids.insert(id, slot);
            }
            Err(why) => log!("couldn't show a notification from {from_name}: {why}"),
        }
    }

    /// The key this computer's notification `id` goes by on the other devices: this device's id
    /// in front, so no two devices' keys are ever the same.
    fn key_for(&self, id: u32) -> String {
        format!("{}:{id}", self.state().credentials.device_id)
    }

    /// A notification was dismissed on another device: take it away here, whether it's a copy
    /// showing here or (when it began here) the real one.
    async fn dismissed_elsewhere(&self, from: &str, key: &str) {
        let close = {
            let mut state = self.state();
            let own = key
                .strip_prefix(&format!("{}:", state.credentials.device_id))
                .and_then(|id| id.parse::<u32>().ok())
                .filter(|_| !state.credentials.device_id.is_empty());
            // A copy from the device that says so; or one of ours; or a copy from a third device
            // (the device it was dismissed on tells everyone, not only where it began).
            let slot = if state.shown.contains_key(&(from.to_string(), key.to_string())) {
                Some((from.to_string(), key.to_string()))
            } else if own.is_some() {
                None
            } else {
                state.shown.keys().find(|(_, k)| k == key).cloned()
            };
            match (slot, own) {
                (Some(slot), _) => {
                    let id = state.shown.remove(&slot);
                    if let Some(id) = id {
                        state.shown_ids.remove(&id);
                    }
                    id
                }
                // Forgotten first, so its closing isn't sent back as news.
                (None, Some(id)) => state.sent.close(&id.to_string()).then_some(id),
                (None, None) => None,
            }
        };
        if let Some(id) = close {
            self.close_notification(id).await;
        }
    }

    /// Something the bus monitor saw.
    async fn saw(self: &Arc<Self>, event: Event) {
        match event {
            Event::Asked { sender, serial, seen } => {
                let mut state = self.state();
                if !state.credentials.signed_in() || !state.config.notifications_out {
                    return;
                }
                if let Some(outgoing) = notes::outgoing(&seen, &state.config.never_mirror) {
                    let now = Instant::now();
                    state.asked.retain(|_, (at, _)| now.duration_since(*at) < Duration::from_secs(30));
                    if state.asked.len() < 256 {
                        state.asked.insert((sender, serial), (now, outgoing));
                    }
                }
            }
            Event::Failed { to, serial } => {
                self.state().asked.remove(&(to, serial));
            }
            Event::Answered { to, serial, id } => {
                let Some((_, outgoing)) = self.state().asked.remove(&(to, serial)) else {
                    return;
                };
                let payload = Payload::Notification {
                    key: self.key_for(id),
                    app: outgoing.app,
                    app_id: outgoing.app_id,
                    title: outgoing.title,
                    text: outgoing.text,
                    at: now_ms(),
                    icon: None,
                };
                let offer = self.state().sent.offer(&id.to_string(), payload, Instant::now());
                match offer {
                    Offer::Send(payload) => {
                        let agent = self.clone();
                        tokio::spawn(async move { agent.tell_others(payload).await });
                    }
                    Offer::Wait(wait) => {
                        let agent = self.clone();
                        tokio::spawn(async move {
                            tokio::time::sleep(wait).await;
                            let due = agent.state().sent.due(&id.to_string(), Instant::now());
                            if let Some(payload) = due {
                                agent.tell_others(payload).await;
                            }
                        });
                    }
                    Offer::Replaced => {}
                }
            }
            Event::Closed { id, reason } => {
                enum Gone {
                    Ring,
                    Copy(String),
                    Ours,
                    Nothing,
                }
                let gone = {
                    let mut state = self.state();
                    if state.ring.as_ref().is_some_and(|r| r.notification == id && id != 0) {
                        Gone::Ring
                    } else if let Some(slot) = state.shown_ids.remove(&id) {
                        state.shown.remove(&slot);
                        // Dismissed by the person here (2): the other devices drop it too. Expired, or
                        // closed because another device said so: nothing to tell.
                        if reason == 2 { Gone::Copy(slot.1) } else { Gone::Nothing }
                    } else if state.sent.close(&id.to_string()) && matches!(reason, 2 | 3) && state.config.notifications_out {
                        Gone::Ours
                    } else {
                        Gone::Nothing
                    }
                };
                match gone {
                    Gone::Ring => self.stop_ringing().await,
                    Gone::Copy(key) => self.tell_others(Payload::Dismiss { key }).await,
                    Gone::Ours => self.tell_others(Payload::Dismiss { key: self.key_for(id) }).await,
                    Gone::Nothing => {}
                }
            }
            Event::Action { id } => {
                if self.state().ring.as_ref().is_some_and(|r| r.notification == id && id != 0) {
                    self.stop_ringing().await;
                }
            }
        }
    }

    // ---- Ring ----

    async fn ring(self: &Arc<Self>, from_name: &str) {
        if self.state().ring.is_some() {
            return;
        }
        let hints = HashMap::from([("urgency", Variant::U8(2)), ("desktop-entry", Variant::from(OWN_DESKTOP_ENTRY))]);
        let words = self.body("Dismiss this to stop the sound.").await;
        let notification = match self
            .notify(
                OWN_APP_NAME,
                0,
                &format!("{from_name} is looking for this computer"),
                &words,
                &["found", "Found it"],
                hints,
                0,
            )
            .await
        {
            Ok(id) => id,
            Err(why) => {
                log!("couldn't show the ring's notification: {why}");
                0
            }
        };
        let agent = self.clone();
        let task = tokio::spawn(async move {
            if !system::ring(RING_FOR).await {
                log!("nothing to ring with: no pw-play, paplay or canberra-gtk-play, or no sound to play");
            }
            // Rung out: the notification goes too.
            let ring = agent.state().ring.take();
            if let Some(ring) = ring.filter(|r| r.notification != 0) {
                agent.close_notification(ring.notification).await;
            }
        });
        let late = {
            let mut state = self.state();
            if state.ring.is_some() {
                Some(task.abort_handle())
            } else {
                state.ring = Some(Ring {
                    task: task.abort_handle(),
                    notification,
                });
                None
            }
        };
        if let Some(second) = late {
            second.abort();
            if notification != 0 {
                self.close_notification(notification).await;
            }
        }
    }

    async fn stop_ringing(&self) {
        let ring = self.state().ring.take();
        if let Some(ring) = ring {
            // The player is stopped with the task.
            ring.task.abort();
            if ring.notification != 0 {
                self.close_notification(ring.notification).await;
            }
        }
    }

    // ---- Media (MPRIS) ----

    async fn media(&self, action: &str) -> Result<(), String> {
        let method = match action {
            "play" => "Play",
            "pause" => "Pause",
            "toggle" => "PlayPause",
            "next" => "Next",
            "previous" => "Previous",
            _ => return Err("not something a player does".into()),
        };
        let connection = self.connection()?;
        let names: Vec<String> = connection
            .call_method(
                Some("org.freedesktop.DBus"),
                "/org/freedesktop/DBus",
                Some("org.freedesktop.DBus"),
                "ListNames",
                &(),
            )
            .await
            .and_then(|reply| reply.body().deserialize())
            .map_err(|e| e.to_string())?;
        let mut players: Vec<String> = names.into_iter().filter(|n| n.starts_with("org.mpris.MediaPlayer2.")).collect();
        players.sort();
        let mut chosen = players.first().cloned();
        for player in &players {
            let status = connection.call_method(
                Some(player.as_str()),
                "/org/mpris/MediaPlayer2",
                Some("org.freedesktop.DBus.Properties"),
                "Get",
                &("org.mpris.MediaPlayer2.Player", "PlaybackStatus"),
            );
            let playing = match tokio::time::timeout(Duration::from_secs(3), status).await {
                Ok(Ok(reply)) => reply
                    .body()
                    .deserialize::<OwnedValue>()
                    .ok()
                    .and_then(|v| String::try_from(v).ok())
                    .is_some_and(|s| s == "Playing"),
                _ => false,
            };
            if playing {
                chosen = Some(player.clone());
                break;
            }
        }
        let player = chosen.ok_or("nothing here is playing anything")?;
        let call = connection.call_method(
            Some(player.as_str()),
            "/org/mpris/MediaPlayer2",
            Some("org.mpris.MediaPlayer2.Player"),
            method,
            &(),
        );
        match tokio::time::timeout(Duration::from_secs(5), call).await {
            Ok(Ok(_)) => Ok(()),
            Ok(Err(error)) => Err(error.to_string()),
            Err(_) => Err("the player didn't answer".into()),
        }
    }

    /// The session is over when its bus is: nothing is left to serve.
    async fn leave_with_the_bus(&self) {
        if let Ok(connection) = self.connection() {
            let alive = connection.call_method(
                Some("org.freedesktop.DBus"),
                "/org/freedesktop/DBus",
                Some("org.freedesktop.DBus"),
                "GetId",
                &(),
            );
            if !matches!(tokio::time::timeout(Duration::from_secs(20), alive).await, Ok(Ok(_))) {
                log!("the session bus went away");
                std::process::exit(0);
            }
        }
    }

    /// Now and then: forget activities that have gone quiet, and notice the bus going away.
    async fn housekeeping(self: Arc<Self>) {
        let mut tick = tokio::time::interval(Duration::from_secs(30));
        loop {
            tick.tick().await;
            let forgot = {
                let mut state = self.state();
                let before = state.activities.len();
                state.activities.retain(|_, a| a.heard.elapsed() < ACTIVITY_LIFE);
                state.published.retain(|_, (at, _)| at.elapsed() < ACTIVITY_LIFE);
                before != state.activities.len()
            };
            if forgot {
                self.emit("ActivitiesChanged").await;
            }
            self.leave_with_the_bus().await;
        }
    }
}

impl Api {
    fn is_token(&self, token: &str) -> bool {
        self.token() == Some(token)
    }
}

/// The D-Bus face of the agent. `data/app.superawesomemagical.Orchestrator1.xml` describes it
/// for the apps and the shell that code against it; a test keeps the two the same.
pub struct Orchestrator {
    agent: Arc<Agent>,
}

#[zbus::interface(name = "app.superawesomemagical.Orchestrator1")]
impl Orchestrator {
    /// JSON: `{"signedIn", "server", "username", "displayName", "connected", "device": {"id", "name"}, "signingIn", "signInUrl"?, "pairingCode"?, "error"?}`.
    #[zbus(out_args("status"))]
    async fn status(&self) -> String {
        self.agent.status().to_string()
    }

    /// A ticket for one of the family's apps, and that app's server.
    #[zbus(out_args("url", "ticket"))]
    async fn sign_in_to(&self, app: &str) -> Result<(String, String), Error> {
        self.agent.ticket(app.trim()).await
    }

    /// JSON, as `GET /api/apps`.
    #[zbus(out_args("apps"))]
    async fn apps(&self) -> Result<String, Error> {
        Ok(self.agent.apps().await?.to_string())
    }

    /// JSON, as `GET /api/devices`.
    #[zbus(out_args("devices"))]
    async fn devices(&self) -> Result<String, Error> {
        Ok(json!(self.agent.devices().await?).to_string())
    }

    /// Seals the payload JSON for a device id (`""`: every other device).
    async fn send(&self, device: &str, payload: &str) -> Result<(), Error> {
        let value: Value = serde_json::from_str(payload).map_err(|_| Error::Failed("The payload isn't JSON".into()))?;
        if !value.is_object() || !value.get("type").is_some_and(Value::is_string) {
            return Err(Error::Failed("A payload is a JSON object with a type".into()));
        }
        if value["type"] == "link" {
            links::check(value["url"].as_str().unwrap_or("")).map_err(|why| Error::Failed(format!("That link can't be sent: {why}")))?;
        }
        self.agent.send(device.trim(), &value).await.map(|_| ())
    }

    /// What the person is doing in an app here, for their other devices. Empty title: stopped.
    async fn publish_activity(&self, app: &str, title: &str, detail: &str, url: &str) -> Result<(), Error> {
        self.agent.publish_activity(app, title, detail, url).await
    }

    /// JSON `[{"device","deviceName","app","title","detail","url","at"}]`: what's happening on the other devices.
    #[zbus(out_args("activities"))]
    async fn activities(&self) -> String {
        self.agent.activities().to_string()
    }

    /// Opens the browser to sign in to this server. Returns once it's on its way: `Changed`
    /// says when it's done, and `Status` how it went.
    async fn sign_in(&self, server: &str) -> Result<(), Error> {
        self.agent.sign_in(server).await
    }

    async fn sign_out(&self) -> Result<(), Error> {
        self.agent.sign_out().await
    }

    /// JSON `{"notificationsOut", "notificationsIn", "clipboard", "links", "neverMirror": []}`.
    #[zbus(out_args("settings"))]
    async fn settings(&self) -> String {
        self.agent.state().config.settings().to_string()
    }

    /// Changes one of `Settings()`'s: "true" or "false", or for neverMirror a JSON list of app names.
    async fn set_setting(&self, key: &str, value: &str) -> Result<(), Error> {
        self.agent.set_setting(key, value)?;
        self.agent.emit("Changed").await;
        Ok(())
    }

    /// Reads the settings and sign-in from disk again (after `sam-orchestrator sign-in` did the signing in itself).
    async fn reload(&self) {
        self.agent.load();
        self.agent.emit("Changed").await;
    }

    /// The status, the devices or the settings changed.
    #[zbus(signal)]
    async fn changed(emitter: &SignalEmitter<'_>) -> zbus::Result<()>;

    /// What `Activities()` gives changed.
    #[zbus(signal)]
    async fn activities_changed(emitter: &SignalEmitter<'_>) -> zbus::Result<()>;
}

/// Runs the agent until it's told to stop.
pub async fn run() -> Result<()> {
    let agent = Agent::new(Store::from_environment()?);
    let connection = zbus::connection::Builder::session()
        .context("There's no session bus to be on (is this a desktop session?)")?
        .serve_at(PATH, Orchestrator { agent: agent.clone() })?
        .build()
        .await
        .context("Couldn't join the session bus")?;
    // One agent a session: a second one leaves, rather than waiting in line for the name.
    match connection
        .request_name_with_flags(NAME, zbus::fdo::RequestNameFlags::DoNotQueue.into())
        .await
    {
        Ok(zbus::fdo::RequestNameReply::PrimaryOwner | zbus::fdo::RequestNameReply::AlreadyOwner) => {}
        Ok(_) | Err(zbus::Error::NameTaken) => bail!("The agent is already running in this session"),
        Err(error) => return Err(error).context("Couldn't take the agent's name on the session bus"),
    }
    let own = connection.unique_name().map(|n| n.to_string()).unwrap_or_default();
    let _ = agent.connection.set(connection);
    log!("version {} on the session bus", env!("CARGO_PKG_VERSION"));

    let (events, mut seen) = mpsc::unbounded_channel();
    let monitoring = agent.clone();
    tokio::spawn(async move {
        // The monitor ends if the bus refuses it or drops it: try again, slowly.
        loop {
            if let Err(error) = monitor::watch(own.clone(), events.clone()).await {
                log!("not watching notifications: {error:#}");
            }
            monitoring.leave_with_the_bus().await;
            tokio::time::sleep(Duration::from_secs(30)).await;
        }
    });
    let watcher = agent.clone();
    tokio::spawn(async move {
        while let Some(event) = seen.recv().await {
            watcher.saw(event).await;
        }
    });
    tokio::spawn(agent.clone().line_loop());
    tokio::spawn(agent.clone().housekeeping());

    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    tokio::select! {
        _ = terminate.recv() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
    agent.stop_ringing().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use zbus::object_server::Interface;

    type Member = (String, String, Vec<(String, String, String)>);

    /// (kind, name, [(argument name, type, direction)]) for each method and signal in an introspection document.
    fn members(xml: &str) -> Vec<Member> {
        fn attribute(tag: &str, name: &str) -> String {
            tag.split(&format!("{name}=\""))
                .nth(1)
                .and_then(|rest| rest.split('"').next())
                .unwrap_or("")
                .to_string()
        }
        let mut found: Vec<Member> = Vec::new();
        for piece in xml.split('<').skip(1) {
            let tag = piece.split('>').next().unwrap_or("");
            if tag.starts_with("method ") || tag.starts_with("signal ") {
                found.push((tag[..6].to_string(), attribute(tag, "name"), Vec::new()));
            } else if tag.starts_with("arg ") {
                let direction = attribute(tag, "direction");
                let direction = if direction.is_empty() { "in".to_string() } else { direction };
                found
                    .last_mut()
                    .expect("an arg inside a member")
                    .2
                    .push((attribute(tag, "name"), attribute(tag, "type"), direction));
            }
        }
        found.sort();
        found
    }

    #[test]
    fn the_published_interface_is_the_one_served() {
        let dir = std::env::temp_dir().join(format!("samo-introspect-{}", std::process::id()));
        let agent = Agent::new(Store::at(&dir, &dir));
        let mut served = String::new();
        Orchestrator { agent }.introspect_to_writer(&mut served, 0);
        let published = include_str!("../data/app.superawesomemagical.Orchestrator1.xml");
        assert!(published.contains(&format!("<interface name=\"{INTERFACE}\">")));
        let (served, published) = (members(&served), members(published));
        assert!(!served.is_empty());
        assert_eq!(
            served, published,
            "data/app.superawesomemagical.Orchestrator1.xml must describe exactly what the daemon serves"
        );
        // Everything docs/PROTOCOL.md lists is there, with its signature.
        let signature = |name: &str| {
            let (_, _, arguments) = served.iter().find(|(_, n, _)| n == name).unwrap_or_else(|| panic!("no {name}"));
            let of = |direction: &str| {
                arguments
                    .iter()
                    .filter(|a| a.2 == direction)
                    .map(|a| a.1.as_str())
                    .collect::<String>()
            };
            (of("in"), of("out"))
        };
        for (name, takes, gives) in [
            ("Status", "", "s"),
            ("SignInTo", "s", "ss"),
            ("Apps", "", "s"),
            ("Devices", "", "s"),
            ("Send", "ss", ""),
            ("PublishActivity", "ssss", ""),
            ("Activities", "", "s"),
            ("SignIn", "s", ""),
            ("SignOut", "", ""),
            ("Settings", "", "s"),
            ("SetSetting", "ss", ""),
            ("Reload", "", ""),
        ] {
            assert_eq!(signature(name), (takes.to_string(), gives.to_string()), "{name}");
        }
        for signal in ["Changed", "ActivitiesChanged"] {
            assert!(
                served
                    .iter()
                    .any(|(kind, name, arguments)| kind == "signal" && name == signal && arguments.is_empty()),
                "{signal}"
            );
        }
    }

    #[test]
    fn status_says_nobody_is_signed_in_at_first() {
        let dir = std::env::temp_dir().join(format!("samo-status-{}", std::process::id()));
        let agent = Agent::new(Store::at(&dir, &dir));
        let status = agent.status();
        assert_eq!(status["signedIn"], false);
        assert_eq!(status["connected"], false);
        assert_eq!(status["signingIn"], false);
        assert_eq!(status["device"]["id"], "");
        assert!(status["device"]["name"].as_str().is_some_and(|n| !n.is_empty()));
        assert_eq!(agent.activities(), json!([]));
        assert!(matches!(agent.api(), Err(Error::NotSignedIn(_))));
    }
}
