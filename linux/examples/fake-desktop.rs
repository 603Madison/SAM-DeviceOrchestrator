//! A stand-in for the desktop, for `tests/e2e.sh`: a notification daemon
//! (`org.freedesktop.Notifications`) and a media player (`org.mpris.MediaPlayer2.fake`) that
//! write what they're asked to a file, one JSON object a line.
//!
//!     fake-desktop <log file>
//!
//! `test.Fake.Dismiss(id)` on the notifications object plays the person dismissing one.

use std::collections::HashMap;
use std::io::Write;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::{Value, json};
use zbus::object_server::SignalEmitter;
use zbus::zvariant::{OwnedValue, Value as Variant};

#[derive(Clone)]
struct Log(Arc<Mutex<std::fs::File>>);

impl Log {
    fn write(&self, line: Value) {
        let mut file = self.0.lock().unwrap();
        writeln!(file, "{line}").unwrap();
        file.flush().unwrap();
    }
}

struct Notifications {
    log: Log,
    next: AtomicU32,
}

fn plain(value: &OwnedValue) -> Value {
    match &**value {
        Variant::Bool(b) => json!(b),
        Variant::U8(n) => json!(n),
        Variant::I32(n) => json!(n),
        Variant::U32(n) => json!(n),
        Variant::Str(s) => json!(s.as_str()),
        _ => json!("…"),
    }
}

#[zbus::interface(name = "org.freedesktop.Notifications")]
impl Notifications {
    #[allow(clippy::too_many_arguments)]
    fn notify(
        &self,
        app_name: &str,
        replaces_id: u32,
        app_icon: &str,
        summary: &str,
        body: &str,
        actions: Vec<String>,
        hints: HashMap<String, OwnedValue>,
        expire_timeout: i32,
    ) -> u32 {
        let id = if replaces_id != 0 {
            replaces_id
        } else {
            self.next.fetch_add(1, Ordering::SeqCst)
        };
        let hints: serde_json::Map<String, Value> = hints.iter().map(|(k, v)| (k.clone(), plain(v))).collect();
        self.log.write(json!({"event": "notify", "id": id, "app": app_name, "replaces": replaces_id, "icon": app_icon, "summary": summary, "body": body,
            "actions": actions, "hints": hints, "timeout": expire_timeout}));
        id
    }

    async fn close_notification(&self, id: u32, #[zbus(signal_emitter)] emitter: SignalEmitter<'_>) -> zbus::fdo::Result<()> {
        self.log.write(json!({"event": "close", "id": id}));
        Self::notification_closed(&emitter, id, 3).await?;
        Ok(())
    }

    fn get_capabilities(&self) -> Vec<String> {
        vec!["body".into(), "body-markup".into(), "actions".into()]
    }

    fn get_server_information(&self) -> (String, String, String, String) {
        ("fake-desktop".into(), "SuperAwesomeMagical".into(), "1".into(), "1.2".into())
    }

    #[zbus(signal)]
    async fn notification_closed(emitter: &SignalEmitter<'_>, id: u32, reason: u32) -> zbus::Result<()>;

    #[zbus(signal)]
    async fn action_invoked(emitter: &SignalEmitter<'_>, id: u32, action_key: &str) -> zbus::Result<()>;
}

struct Fake {
    log: Log,
}

#[zbus::interface(name = "test.Fake")]
impl Fake {
    /// The person dismisses a notification (reason 2).
    async fn dismiss(&self, id: u32, #[zbus(connection)] connection: &zbus::Connection) -> zbus::fdo::Result<()> {
        self.log.write(json!({"event": "dismissed", "id": id}));
        connection
            .emit_signal(
                None::<&str>,
                "/org/freedesktop/Notifications",
                "org.freedesktop.Notifications",
                "NotificationClosed",
                &(id, 2u32),
            )
            .await?;
        Ok(())
    }

    /// A notification times out (reason 1).
    async fn expire(&self, id: u32, #[zbus(connection)] connection: &zbus::Connection) -> zbus::fdo::Result<()> {
        connection
            .emit_signal(
                None::<&str>,
                "/org/freedesktop/Notifications",
                "org.freedesktop.Notifications",
                "NotificationClosed",
                &(id, 1u32),
            )
            .await?;
        Ok(())
    }
}

struct Player {
    log: Log,
}

#[zbus::interface(name = "org.mpris.MediaPlayer2.Player")]
impl Player {
    #[zbus(property)]
    fn playback_status(&self) -> String {
        "Playing".into()
    }
    fn play(&self) {
        self.log.write(json!({"event": "media", "action": "Play"}));
    }
    fn pause(&self) {
        self.log.write(json!({"event": "media", "action": "Pause"}));
    }
    fn play_pause(&self) {
        self.log.write(json!({"event": "media", "action": "PlayPause"}));
    }
    fn next(&self) {
        self.log.write(json!({"event": "media", "action": "Next"}));
    }
    fn previous(&self) {
        self.log.write(json!({"event": "media", "action": "Previous"}));
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args().nth(1).ok_or("fake-desktop <log file>")?;
    let log = Log(Arc::new(Mutex::new(
        std::fs::OpenOptions::new().create(true).append(true).open(path)?,
    )));
    let _connection = zbus::connection::Builder::session()?
        .name("org.freedesktop.Notifications")?
        .name("org.mpris.MediaPlayer2.fake")?
        .serve_at(
            "/org/freedesktop/Notifications",
            Notifications {
                log: log.clone(),
                next: AtomicU32::new(1),
            },
        )?
        .serve_at("/org/freedesktop/Notifications", Fake { log: log.clone() })?
        .serve_at("/org/mpris/MediaPlayer2", Player { log: log.clone() })?
        .build()
        .await?;
    log.write(json!({"event": "ready"}));
    std::future::pending::<()>().await;
    Ok(())
}
