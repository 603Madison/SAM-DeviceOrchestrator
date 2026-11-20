//! Watching the session bus for notifications: every `org.freedesktop.Notifications.Notify`
//! call, the id each is given (in the method's return, matched by its reply serial), and the
//! signals for closing and actions. Done as a bus monitor on a connection of its own, so it
//! works whichever notification daemon runs (on SuperAwesomeMagicalLinux, the shell).

use std::collections::HashMap;

use anyhow::{Context, Result};
use futures_util::StreamExt;
use tokio::sync::mpsc::UnboundedSender;
use zbus::message::Type;
use zbus::zvariant::OwnedValue;

use crate::notes::{MIRRORED_HINT, Seen};

pub const NOTIFICATIONS: &str = "org.freedesktop.Notifications";

#[derive(Debug)]
pub enum Event {
    /// An app asked for a notification: the call's sender and serial, to match its id by.
    Asked { sender: String, serial: u32, seen: Seen },
    /// The notification daemon answered a call with the notification's id.
    Answered { to: String, serial: u32, id: u32 },
    /// The call failed: there'll be no id.
    Failed { to: String, serial: u32 },
    Closed { id: u32, reason: u32 },
    Action { id: u32, action: String },
}

const RULES: &[&str] = &[
    "type='method_call',interface='org.freedesktop.Notifications',member='Notify'",
    "type='method_return',sender='org.freedesktop.Notifications'",
    "type='error',sender='org.freedesktop.Notifications'",
    "type='signal',interface='org.freedesktop.Notifications',member='NotificationClosed'",
    "type='signal',interface='org.freedesktop.Notifications',member='ActionInvoked'",
];

type NotifyBody = (String, u32, String, String, String, Vec<String>, HashMap<String, OwnedValue>, i32);

fn truthy(value: &OwnedValue) -> bool {
    use zbus::zvariant::Value;
    match &**value {
        Value::Bool(b) => *b,
        Value::U8(n) => *n != 0,
        Value::I16(n) => *n != 0,
        Value::U16(n) => *n != 0,
        Value::I32(n) => *n != 0,
        Value::U32(n) => *n != 0,
        Value::I64(n) => *n != 0,
        Value::U64(n) => *n != 0,
        Value::Str(s) => matches!(s.as_str(), "true" | "1"),
        _ => true,
    }
}

fn text(value: &OwnedValue) -> String {
    match &**value {
        zbus::zvariant::Value::Str(s) => s.to_string(),
        _ => String::new(),
    }
}

/// Watches until the bus goes away. `own` is the agent's own name on the bus: what it raises
/// itself is marked so, and never mirrored.
pub async fn watch(own: String, events: UnboundedSender<Event>) -> Result<()> {
    let connection = zbus::connection::Builder::session()?.build().await.context("Couldn't connect to the session bus")?;
    let rules: Vec<&str> = RULES.to_vec();
    connection
        .call_method(Some("org.freedesktop.DBus"), "/org/freedesktop/DBus", Some("org.freedesktop.DBus.Monitoring"), "BecomeMonitor", &(rules, 0u32))
        .await
        .context("The session bus wouldn't let the agent watch notifications")?;
    let mut stream = zbus::MessageStream::from(&connection);
    while let Some(message) = stream.next().await {
        let Ok(message) = message else { continue };
        let header = message.header();
        let sender = header.sender().map(|s| s.to_string()).unwrap_or_default();
        let event = match header.message_type() {
            Type::MethodCall if header.member().is_some_and(|m| m.as_str() == "Notify") && header.interface().is_some_and(|i| i.as_str() == NOTIFICATIONS) => {
                let Ok((app_name, _replaces, _icon, summary, body, _actions, hints, _timeout)) = message.body().deserialize::<NotifyBody>() else {
                    continue;
                };
                let seen = Seen {
                    app_name,
                    summary,
                    body,
                    desktop_entry: hints.get("desktop-entry").map(text).unwrap_or_default(),
                    transient: hints.get("transient").is_some_and(truthy),
                    mirrored: hints.contains_key(MIRRORED_HINT),
                    own: sender == own,
                };
                Event::Asked { sender, serial: header.primary().serial_num().get(), seen }
            }
            Type::MethodReturn => {
                let (Some(serial), Some(to)) = (header.reply_serial(), header.destination()) else { continue };
                // Other answers from the same program (it may own more names than this one) aren't a lone id.
                let Ok(id) = message.body().deserialize::<u32>() else { continue };
                Event::Answered { to: to.to_string(), serial: serial.get(), id }
            }
            Type::Error => {
                let (Some(serial), Some(to)) = (header.reply_serial(), header.destination()) else { continue };
                Event::Failed { to: to.to_string(), serial: serial.get() }
            }
            Type::Signal if header.interface().is_some_and(|i| i.as_str() == NOTIFICATIONS) => match header.member().map(|m| m.to_string()).as_deref() {
                Some("NotificationClosed") => match message.body().deserialize::<(u32, u32)>() {
                    Ok((id, reason)) => Event::Closed { id, reason },
                    Err(_) => continue,
                },
                Some("ActionInvoked") => match message.body().deserialize::<(u32, String)>() {
                    Ok((id, action)) => Event::Action { id, action },
                    Err(_) => continue,
                },
                _ => continue,
            },
            _ => continue,
        };
        if events.send(event).is_err() {
            break;
        }
    }
    Ok(())
}
