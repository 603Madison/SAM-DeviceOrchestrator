//! The same as the D-Bus interface, from a shell: every command asks the agent (which D-Bus
//! starts when it isn't running), and says how it went in a line or two.

use std::time::Duration;

use anyhow::{Result, anyhow, bail};
use clap::{Parser, Subcommand};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use zbus::zvariant::DynamicType;

use crate::api::{self, Device};
use crate::daemon::{INTERFACE, NAME, PATH};
use crate::store::{Credentials, Store};
use crate::{links, signin, system};

#[derive(Parser)]
#[command(
    name = "sam-orchestrator",
    version,
    about = "SuperAwesomeMagicalOrchestrator on this computer: one sign-in for every app, and your devices working together"
)]
pub struct Cli {
    /// Print JSON instead of words (status, devices, apps, ticket, settings, activities)
    #[arg(long, global = true)]
    json: bool,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Run the agent (the session's D-Bus starts it when something asks)
    Daemon,
    /// Who's signed in, and whether the server is reachable
    Status,
    /// Your devices
    Devices,
    /// The family's apps this orchestrator knows
    Apps,
    /// Sign this computer in: opens the browser at the server
    SignIn {
        /// The orchestrator's address, like https://orchestrator.example.com
        server: String,
        /// For a computer with no browser: read a device token from standard input instead
        #[arg(long)]
        token_stdin: bool,
    },
    /// Sign this computer out, and take it off your devices
    SignOut,
    /// A sign-in ticket for one of the family's apps
    Ticket {
        /// The app's id: music, video, health, maps, email
        app: String,
    },
    /// Open a link on another device
    SendLink {
        /// The device's name (or the start of it), or its id
        device: String,
        url: String,
    },
    /// Put this computer's clipboard on another device (or all of them)
    SendClipboard { device: Option<String> },
    /// Make a device ring until it's found
    Ring {
        device: String,
        /// Stop it ringing
        #[arg(long)]
        stop: bool,
    },
    /// See the settings, or change one: settings clipboard false
    Settings {
        key: Option<String>,
        #[arg(requires = "key")]
        value: Option<String>,
    },
    /// What's happening on your other devices
    Activities,
    /// Start the agent if someone's signed in (what the desktop runs at login)
    #[command(hide = true)]
    Autostart,
}

pub async fn run(cli: Cli) -> Result<()> {
    let json = cli.json;
    match cli.command {
        Command::Daemon => crate::daemon::run().await,
        Command::Autostart => {
            // Signed in: wake the agent (through D-Bus, so it runs as the session's service).
            if Store::from_environment()?.credentials().signed_in() {
                let _ = Agent::connect().await?.call::<_, String>("Status", &()).await;
            }
            Ok(())
        }
        Command::Status => {
            let status: Value = Agent::connect().await?.json("Status", &()).await?;
            if json {
                println!("{status}");
            } else {
                println!("{}", status_words(&status));
            }
            Ok(())
        }
        Command::Devices => {
            let devices: Value = Agent::connect().await?.json("Devices", &()).await?;
            if json {
                println!("{devices}");
                return Ok(());
            }
            let devices: Vec<Device> = serde_json::from_value(devices)?;
            for device in &devices {
                println!("{}", device_words(device));
            }
            Ok(())
        }
        Command::Apps => {
            let apps: Value = Agent::connect().await?.json("Apps", &()).await?;
            if json {
                println!("{apps}");
                return Ok(());
            }
            let list = apps.as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                println!("No apps are listed yet. An admin adds them on the orchestrator's Admin page.");
            }
            let width = list.iter().map(|a| a["id"].as_str().unwrap_or("").len()).max().unwrap_or(0);
            for app in list {
                println!(
                    "{:width$}  {}  {}",
                    app["id"].as_str().unwrap_or(""),
                    app["name"].as_str().unwrap_or(""),
                    app["url"].as_str().unwrap_or("")
                );
            }
            Ok(())
        }
        Command::SignIn { server, token_stdin } => sign_in(&server, token_stdin).await,
        Command::SignOut => {
            Agent::connect().await?.call::<_, ()>("SignOut", &()).await?;
            println!("Signed out. This computer is off your devices.");
            Ok(())
        }
        Command::Ticket { app } => {
            let (url, ticket): (String, String) = Agent::connect().await?.call("SignInTo", &(app.as_str(),)).await?;
            if json {
                println!("{}", json!({"app": app, "url": url, "ticket": ticket}));
            } else {
                println!("{ticket}");
            }
            Ok(())
        }
        Command::SendLink { device, url } => {
            let address = links::check(&url)
                .map_err(|why| anyhow!("That link can't be sent: {why}"))?
                .address()
                .to_string();
            let agent = Agent::connect().await?;
            let device = agent.device(&device).await?;
            agent
                .send(&device.id, &json!({"type": "link", "url": address, "title": ""}))
                .await?;
            println!("{}", sent_words("The link", &device));
            Ok(())
        }
        Command::SendClipboard { device } => {
            let text = system::clipboard().await.map_err(|why| anyhow!(why))?;
            let agent = Agent::connect().await?;
            let payload = json!({"type": "clipboard", "text": text});
            match device {
                Some(wanted) => {
                    let device = agent.device(&wanted).await?;
                    agent.send(&device.id, &payload).await?;
                    println!("{}", sent_words("The clipboard", &device));
                }
                None => {
                    agent.send("", &payload).await?;
                    println!("The clipboard is on its way to your other devices.");
                }
            }
            Ok(())
        }
        Command::Ring { device, stop } => {
            let agent = Agent::connect().await?;
            let device = agent.device(&device).await?;
            agent.send(&device.id, &json!({"type": "ring", "on": !stop})).await?;
            match (stop, device.online) {
                (true, _) => println!("{} will stop ringing.", device.name),
                (false, true) => println!(
                    "{} is ringing. Stop it with: sam-orchestrator ring --stop \"{}\"",
                    device.name, device.name
                ),
                (false, false) => println!("{} is away: it'll ring if it's back within five minutes.", device.name),
            }
            Ok(())
        }
        Command::Settings { key, value } => {
            let agent = Agent::connect().await?;
            if let (Some(key), Some(value)) = (&key, &value) {
                agent.call::<_, ()>("SetSetting", &(key.as_str(), value.as_str())).await?;
            } else if key.is_some() {
                bail!(
                    "Say what to change it to: sam-orchestrator settings {} true",
                    key.unwrap_or_default()
                );
            }
            let settings: Value = agent.json("Settings", &()).await?;
            if json {
                println!("{settings}");
            } else {
                println!("{}", settings_words(&settings));
            }
            Ok(())
        }
        Command::Activities => {
            let activities: Value = Agent::connect().await?.json("Activities", &()).await?;
            if json {
                println!("{activities}");
                return Ok(());
            }
            let list = activities.as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                println!("Nothing's happening on your other devices right now.");
            }
            for a in list {
                let detail = a["detail"]
                    .as_str()
                    .filter(|d| !d.is_empty())
                    .map(|d| format!(" ({d})"))
                    .unwrap_or_default();
                println!(
                    "{}: {} on {}{detail}",
                    a["deviceName"].as_str().unwrap_or(""),
                    a["title"].as_str().unwrap_or(""),
                    a["app"].as_str().unwrap_or("")
                );
            }
            Ok(())
        }
    }
}

async fn sign_in(server: &str, token_stdin: bool) -> Result<()> {
    let server = api::server_address(server).map_err(|why| anyhow!(why))?;
    if api::is_plain_http(&server) {
        eprintln!("Careful: {server} isn't https. Anyone on the network between here and it can read your sign-in.");
        eprintln!("That's fine on a home network you trust; anywhere else, use the https address.");
    }
    let store = Store::from_environment()?;
    let running = Agent::running().await;
    if token_stdin {
        let mut token = String::new();
        std::io::stdin().read_line(&mut token)?;
        let token = token.trim();
        if token.is_empty() || token.chars().any(|c| c.is_whitespace() || c.is_control()) {
            bail!("No token came in on standard input");
        }
        let http = api::http_client();
        let before = (store.credentials(), store.config().server);
        let credentials = signin::establish(&http, &store, &server, token).await?;
        forget_earlier(&http, before, &credentials).await;
        if let Some(agent) = running {
            agent.call::<_, ()>("Reload", &()).await?;
        }
        println!(
            "{}",
            signed_in_words(&credentials.display_name, &credentials.username, &credentials.device_name)
        );
        return Ok(());
    }
    let Some(agent) = running else {
        // No agent to pick it up: do it here; the agent reads the sign-in when it starts.
        let waiting = signin::start(&server, &system::device_name()).await?;
        println!("{}", opening_words(&waiting.pairing_code, &waiting.url));
        if let Err(why) = system::open(&waiting.url).await {
            eprintln!("({why})");
        }
        let http = api::http_client();
        let before = (store.credentials(), store.config().server);
        let credentials = waiting.finish(&http, &store).await?;
        forget_earlier(&http, before, &credentials).await;
        println!(
            "{}",
            signed_in_words(&credentials.display_name, &credentials.username, &credentials.device_name)
        );
        return Ok(());
    };
    agent.call::<_, ()>("SignIn", &(server.as_str(),)).await?;
    let mut told = false;
    let until = tokio::time::Instant::now() + signin::PATIENCE + Duration::from_secs(15);
    loop {
        let status: Value = agent.json("Status", &()).await?;
        if status["signingIn"] != true {
            if let Some(error) = status["error"].as_str().filter(|e| !e.is_empty()) {
                bail!("{error}");
            }
            if status["signedIn"] != true {
                bail!("The sign-in didn't finish");
            }
            println!(
                "{}",
                signed_in_words(
                    status["displayName"].as_str().unwrap_or(""),
                    status["username"].as_str().unwrap_or(""),
                    status["device"]["name"].as_str().unwrap_or("")
                )
            );
            return Ok(());
        }
        if !told {
            told = true;
            println!(
                "{}",
                opening_words(
                    status["pairingCode"].as_str().unwrap_or(""),
                    status["signInUrl"].as_str().unwrap_or("")
                )
            );
        }
        if tokio::time::Instant::now() > until {
            bail!("Nobody signed in within five minutes. Try again when you're ready.");
        }
        tokio::time::sleep(Duration::from_millis(400)).await;
    }
}

/// The sign-in a new one replaced: its device entry goes with it.
async fn forget_earlier(http: &reqwest::Client, (before, server): (Credentials, String), now: &Credentials) {
    if let Some(old) = before
        .token
        .as_deref()
        .filter(|old| Some(*old) != now.token.as_deref() && !before.device_id.is_empty() && !server.is_empty())
    {
        let _ = api::Api::new(http, &server, Some(old))
            .delete(&format!("/api/devices/{}", before.device_id))
            .await;
    }
}

fn opening_words(code: &str, url: &str) -> String {
    format!(
        "Your code: {code}\nThe sign-in page shows the same one. Only carry on there if they match.\n\nOpening your browser. If it doesn't open, go to:\n  {url}"
    )
}

fn signed_in_words(display_name: &str, username: &str, device: &str) -> String {
    let who = if display_name.is_empty() || display_name == username {
        username.to_string()
    } else {
        format!("{display_name} ({username})")
    };
    format!("You're signed in as {who}. This computer is \"{device}\" on your devices.")
}

fn status_words(status: &Value) -> String {
    let text = |name: &str| status[name].as_str().unwrap_or("");
    if status["signedIn"] != true {
        let mut words = String::from("Nobody's signed in on this computer.");
        if status["signingIn"] == true {
            words.push_str(&format!(
                "\nA sign-in is waiting in your browser, with the code {}:\n  {}",
                text("pairingCode"),
                text("signInUrl")
            ));
        } else if !text("error").is_empty() {
            words.push_str(&format!("\nThe last sign-in didn't work: {}", text("error")));
        } else {
            words.push_str("\nSign in with: sam-orchestrator sign-in https://your-orchestrator");
        }
        return words;
    }
    let who = if text("displayName").is_empty() || text("displayName") == text("username") {
        text("username").to_string()
    } else {
        format!("{} ({})", text("displayName"), text("username"))
    };
    let line = if status["connected"] == true {
        "Connected."
    } else {
        "Not connected right now; it keeps trying."
    };
    format!(
        "Signed in to {} as {who}.\nThis computer is \"{}\". {line}",
        text("server"),
        status["device"]["name"].as_str().unwrap_or("")
    )
}

fn device_words(device: &Device) -> String {
    let kind = match device.kind.as_str() {
        "LINUX" => "Linux",
        "ANDROID" => "Android",
        _ => "Device",
    };
    let mut words = format!("{}{}", device.name, if device.current { " (this computer)" } else { "" });
    words.push_str(&format!(": {kind}"));
    if !device.model.is_empty() {
        words.push_str(&format!(", {}", device.model));
    }
    words.push_str(if device.online { ", connected" } else { ", away" });
    if let Some(battery) = device.battery {
        words.push_str(&format!(
            ", battery {}%{}",
            battery.level,
            if battery.charging { " and charging" } else { "" }
        ));
    }
    words
}

fn sent_words(what: &str, device: &Device) -> String {
    if device.online {
        format!("{what} is on {} now.", device.name)
    } else {
        format!("{} is away: it'll get {} when it's back.", device.name, what.to_lowercase())
    }
}

fn settings_words(settings: &Value) -> String {
    let on = |name: &str| if settings[name] == true { "on" } else { "off" };
    let never: Vec<&str> = settings["neverMirror"]
        .as_array()
        .map(|list| list.iter().filter_map(Value::as_str).collect())
        .unwrap_or_default();
    format!(
        "notificationsOut  {:3}  This computer's notifications go to your other devices\n\
         notificationsIn   {:3}  Your other devices' notifications show here\n\
         clipboard         {:3}  Clipboards sent here are taken\n\
         links             {:3}  Links sent here are opened\n\
         neverMirror       {}",
        on("notificationsOut"),
        on("notificationsIn"),
        on("clipboard"),
        on("links"),
        if never.is_empty() {
            "(no apps left out)".to_string()
        } else {
            never.join(", ")
        }
    )
}

/// The device meant by a name, the start of one, or an id. Never this computer.
pub fn find_device<'a>(devices: &'a [Device], wanted: &str) -> Result<&'a Device, String> {
    let wanted = wanted.trim();
    let lower = wanted.to_lowercase();
    if wanted.is_empty() {
        return Err("Which device?".into());
    }
    let others: Vec<&Device> = devices.iter().filter(|d| !d.current).collect();
    let names = || others.iter().map(|d| d.name.as_str()).collect::<Vec<_>>().join(", ");
    if let Some(device) = devices.iter().find(|d| d.id == wanted) {
        return if device.current {
            Err("That's this computer".into())
        } else {
            Ok(device)
        };
    }
    let pick = |matches: Vec<&'a Device>| match matches.len() {
        0 => None,
        1 => Some(Ok(matches[0])),
        _ => Some(Err(format!(
            "More than one device is called that ({}). Use its id from: sam-orchestrator devices --json",
            matches.iter().map(|d| d.name.as_str()).collect::<Vec<_>>().join(", ")
        ))),
    };
    let exact = others.iter().copied().filter(|d| d.name.to_lowercase() == lower).collect();
    let starts = others
        .iter()
        .copied()
        .filter(|d| d.name.to_lowercase().starts_with(&lower))
        .collect();
    let holds = others.iter().copied().filter(|d| d.name.to_lowercase().contains(&lower)).collect();
    if let Some(found) = pick(exact).or_else(|| pick(starts)).or_else(|| pick(holds)) {
        return found;
    }
    if devices.iter().any(|d| d.current && d.name.to_lowercase() == lower) {
        return Err("That's this computer".into());
    }
    Err(if others.is_empty() {
        "You have no other devices signed in yet".to_string()
    } else {
        format!("No device is called {wanted}. There's: {}", names())
    })
}

/// The agent, over D-Bus.
struct Agent {
    proxy: zbus::Proxy<'static>,
}

impl Agent {
    async fn connect() -> Result<Self> {
        let connection = zbus::Connection::session()
            .await
            .map_err(|_| anyhow!("There's no session bus to reach the agent on (is this a desktop session?)"))?;
        Ok(Self {
            proxy: zbus::Proxy::new(&connection, NAME, PATH, INTERFACE).await?,
        })
    }

    /// The agent, only if it's running already (asking doesn't start it).
    async fn running() -> Option<Self> {
        let agent = Self::connect().await.ok()?;
        let reply = agent
            .proxy
            .connection()
            .call_method(
                Some("org.freedesktop.DBus"),
                "/org/freedesktop/DBus",
                Some("org.freedesktop.DBus"),
                "NameHasOwner",
                &(NAME,),
            )
            .await
            .ok()?;
        reply.body().deserialize::<bool>().ok()?.then_some(agent)
    }

    async fn call<B: Serialize + DynamicType, R: DeserializeOwned + zbus::zvariant::Type>(&self, method: &str, body: &B) -> Result<R> {
        self.proxy.call(method, body).await.map_err(|error| match error {
            zbus::Error::MethodError(name, words, _) => {
                let name = name.as_str();
                if name.ends_with(".ServiceUnknown") || name.ends_with(".NameHasNoOwner") || name.contains(".Spawn.") {
                    anyhow!("The agent isn't running and couldn't be started. Start it with: sam-orchestrator daemon")
                } else {
                    anyhow!(
                        "{}",
                        words.unwrap_or_else(|| name.rsplit('.').next().unwrap_or("It didn't work").to_string())
                    )
                }
            }
            other => anyhow!("Couldn't ask the agent: {other}"),
        })
    }

    async fn json<B: Serialize + DynamicType>(&self, method: &str, body: &B) -> Result<Value> {
        Ok(serde_json::from_str(&self.call::<_, String>(method, body).await?)?)
    }

    async fn device(&self, wanted: &str) -> Result<Device> {
        let devices: Vec<Device> = serde_json::from_value(self.json("Devices", &()).await?)?;
        find_device(&devices, wanted).cloned().map_err(|why| anyhow!(why))
    }

    async fn send(&self, device: &str, payload: &Value) -> Result<()> {
        self.call("Send", &(device, payload.to_string().as_str())).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    fn device(id: &str, name: &str, current: bool) -> Device {
        Device {
            id: id.into(),
            name: name.into(),
            kind: "ANDROID".into(),
            model: String::new(),
            public_key: String::new(),
            capabilities: vec![],
            online: true,
            last_seen_at: 0,
            created_at: 0,
            battery: None,
            current,
        }
    }

    #[test]
    fn the_commands_are_the_protocols() {
        Cli::command().debug_assert();
        for line in [
            "status",
            "devices",
            "apps",
            "sign-in https://o.example.com",
            "sign-out",
            "ticket music",
            "send-link Pixel https://example.com",
            "send-clipboard",
            "send-clipboard Pixel",
            "ring Pixel",
            "status --json",
            "--json devices",
            "ticket music --json",
            "daemon",
            "settings",
            "settings links false",
            "ring --stop Pixel",
            "sign-in --token-stdin https://o.example.com",
            "activities --json",
        ] {
            let words = std::iter::once("sam-orchestrator").chain(line.split(' '));
            assert!(Cli::try_parse_from(words).is_ok(), "{line}");
        }
        for line in ["", "ticket", "send-link Pixel", "ring", "sign-in", "frobnicate"] {
            assert!(
                Cli::try_parse_from(std::iter::once("sam-orchestrator").chain(line.split(' ').filter(|w| !w.is_empty()))).is_err(),
                "{line}"
            );
        }
    }

    #[test]
    fn devices_are_found_by_name_or_id() {
        let devices = [
            device("d1", "Den laptop", true),
            device("d2", "Pixel 9", false),
            device("d3", "Pixel Tablet", false),
            device("d4", "Kitchen", false),
        ];
        assert_eq!(find_device(&devices, "d2").unwrap().id, "d2");
        assert_eq!(find_device(&devices, "pixel 9").unwrap().id, "d2");
        assert_eq!(find_device(&devices, "kit").unwrap().id, "d4");
        assert_eq!(find_device(&devices, "tablet").unwrap().id, "d3");
        assert!(find_device(&devices, "pixel").unwrap_err().contains("More than one"));
        assert_eq!(find_device(&devices, "d1").unwrap_err(), "That's this computer");
        assert_eq!(find_device(&devices, "Den laptop").unwrap_err(), "That's this computer");
        assert!(
            find_device(&devices, "fridge")
                .unwrap_err()
                .contains("Pixel 9, Pixel Tablet, Kitchen")
        );
        assert!(find_device(&devices[..1], "anything").unwrap_err().contains("no other devices"));
        assert!(find_device(&devices, " ").is_err());
    }

    #[test]
    fn words_are_short_and_plain() {
        assert!(status_words(&json!({"signedIn": false})).starts_with("Nobody's signed in"));
        let signed_in = json!({"signedIn": true, "server": "https://o.example.com", "username": "maddie", "displayName": "Maddie", "connected": true, "device": {"id": "d1", "name": "Den laptop"}});
        assert_eq!(
            status_words(&signed_in),
            "Signed in to https://o.example.com as Maddie (maddie).\nThis computer is \"Den laptop\". Connected."
        );
        let mut phone = device("d2", "Pixel 9", false);
        phone.model = "Pixel 9".into();
        phone.battery = Some(api::Battery { level: 81, charging: true });
        assert_eq!(
            device_words(&phone),
            "Pixel 9: Android, Pixel 9, connected, battery 81% and charging"
        );
        assert_eq!(sent_words("The link", &phone), "The link is on Pixel 9 now.");
        phone.online = false;
        assert_eq!(
            sent_words("The link", &phone),
            "Pixel 9 is away: it'll get the link when it's back."
        );
    }
}
