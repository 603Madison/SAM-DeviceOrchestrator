//! This computer: what it's called, its battery, and the desktop's tools the agent runs
//! (`xdg-open`, `wl-copy`, `wl-paste`, a sound player). Tools are always run directly with
//! their arguments, never through a shell.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use tokio::io::AsyncWriteExt;
use tokio::process::Command;

use crate::api::Battery;

/// The most clipboard text sent or taken: a sealed message carries about 48 KB.
pub const MAX_CLIPBOARD: usize = 40_000;

/// Where `/sys` is (somewhere else in tests).
fn sys() -> PathBuf {
    std::env::var_os("SAM_ORCHESTRATOR_SYSFS").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("/sys"))
}

/// What to call this computer: its pretty name if it has one, or its host name tidied up.
pub fn device_name() -> String {
    let pretty = std::fs::read_to_string("/etc/machine-info").ok().and_then(|text| pretty_hostname(&text));
    let host = std::fs::read_to_string("/proc/sys/kernel/hostname").or_else(|_| std::fs::read_to_string("/etc/hostname")).unwrap_or_default();
    pretty.unwrap_or_else(|| prettify(&host))
}

/// `PRETTY_HOSTNAME` from `/etc/machine-info`.
pub fn pretty_hostname(machine_info: &str) -> Option<String> {
    let value = machine_info.lines().find_map(|line| line.trim().strip_prefix("PRETTY_HOSTNAME="))?.trim();
    let value = value.strip_prefix('"').and_then(|v| v.strip_suffix('"')).or_else(|| value.strip_prefix('\'').and_then(|v| v.strip_suffix('\''))).unwrap_or(value);
    let value: String = value.replace("\\\"", "\"").chars().filter(|c| !c.is_control()).take(64).collect();
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_string())
}

/// "maddies-laptop.lan" → "Maddies laptop".
pub fn prettify(host: &str) -> String {
    let short = host.trim().split('.').next().unwrap_or("");
    let words: Vec<&str> = short.split(['-', '_']).filter(|w| !w.is_empty()).collect();
    let joined: String = words.join(" ").chars().filter(|c| !c.is_control()).take(64).collect();
    let mut letters = joined.chars();
    match letters.next() {
        Some(first) if joined != "localhost" => first.to_uppercase().chain(letters).collect(),
        _ => "Linux computer".to_string(),
    }
}

/// What the computer calls itself ("ThinkPad X1 Carbon"), when it says.
pub fn model() -> String {
    let dmi = sys().join("class/dmi/id");
    let read = |name: &str| std::fs::read_to_string(dmi.join(name)).unwrap_or_default().trim().to_string();
    let placeholder = |text: &str| {
        let lower = text.to_lowercase();
        text.is_empty() || ["to be filled", "system product name", "default string", "not specified", "none", "unknown", "o.e.m"].iter().any(|p| lower.contains(p))
    };
    // Lenovo keeps the name people know in product_version ("ThinkPad X1 Carbon Gen 9").
    let name = read("product_name");
    let version = read("product_version");
    let model = if read("sys_vendor").to_lowercase().contains("lenovo") && !placeholder(&version) { version } else { name };
    if placeholder(&model) { String::new() } else { model.chars().filter(|c| !c.is_control()).take(64).collect() }
}

/// The battery, if there is one: the average of the batteries' levels, charging when one is or
/// the computer is plugged in.
pub fn battery() -> Option<Battery> {
    battery_at(&sys().join("class/power_supply"))
}

pub fn battery_at(power_supply: &Path) -> Option<Battery> {
    let read = |dir: &Path, name: &str| std::fs::read_to_string(dir.join(name)).ok().map(|t| t.trim().to_string());
    let mut levels = Vec::new();
    let mut charging = false;
    let mut plugged = false;
    for entry in std::fs::read_dir(power_supply).ok()?.flatten() {
        let dir = entry.path();
        let kind = read(&dir, "type").unwrap_or_default();
        let name = entry.file_name().to_string_lossy().to_string();
        if kind == "Mains" || kind.starts_with("USB") {
            plugged |= read(&dir, "online").as_deref() == Some("1");
        } else if name.starts_with("BAT") || (kind == "Battery" && read(&dir, "scope").as_deref() != Some("Device")) {
            if let Some(level) = read(&dir, "capacity").and_then(|c| c.parse::<i32>().ok()) {
                levels.push(level.clamp(0, 100));
                charging |= read(&dir, "status").as_deref() == Some("Charging");
            }
        }
    }
    if levels.is_empty() {
        return None;
    }
    Some(Battery { level: levels.iter().sum::<i32>() / levels.len() as i32, charging: charging || plugged })
}

// ---- Tools ----

fn missing(tool: &str, package: &str) -> String {
    format!("{tool} isn't installed here ({package})")
}

/// A command with the display it needs. A service started before the desktop was (or by D-Bus)
/// may not have been told where the Wayland display is: look for it.
fn desktop_command(tool: &str) -> Command {
    let mut command = Command::new(tool);
    for (name, value) in desktop_environment() {
        command.env(name, value);
    }
    command
}

fn desktop_environment() -> Vec<(&'static str, String)> {
    if std::env::var_os("WAYLAND_DISPLAY").is_some() || std::env::var_os("DISPLAY").is_some() {
        return Vec::new();
    }
    let Some(runtime) = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from) else {
        return Vec::new();
    };
    let mut sockets: Vec<String> = std::fs::read_dir(runtime)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.file_name().to_string_lossy().to_string())
        .filter(|n| n.starts_with("wayland-") && !n.ends_with(".lock"))
        .collect();
    sockets.sort();
    sockets.into_iter().next().map(|socket| vec![("WAYLAND_DISPLAY", socket)]).unwrap_or_default()
}

/// Puts `text` on the clipboard (`wl-copy`, the text on its standard input).
pub async fn set_clipboard(text: &str) -> Result<(), String> {
    let mut child = desktop_command("wl-copy")
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| if e.kind() == std::io::ErrorKind::NotFound { missing("wl-copy", "the wl-clipboard package") } else { format!("Couldn't run wl-copy: {e}") })?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin.write_all(text.as_bytes()).await.map_err(|_| "wl-copy stopped before taking the text".to_string())?;
        stdin.shutdown().await.ok();
    }
    // wl-copy leaves a copy of itself behind to hold the clipboard; the one we started ends at once.
    match tokio::time::timeout(Duration::from_secs(10), child.wait()).await {
        Ok(Ok(status)) if status.success() => Ok(()),
        Ok(Ok(_)) => Err("wl-copy couldn't set the clipboard (is this a Wayland session?)".into()),
        Ok(Err(e)) => Err(format!("Couldn't run wl-copy: {e}")),
        Err(_) => {
            let _ = child.start_kill();
            Err("wl-copy took too long".into())
        }
    }
}

/// What's on the clipboard (`wl-paste --no-newline`).
pub async fn clipboard() -> Result<String, String> {
    let run = desktop_command("wl-paste").arg("--no-newline").stdin(Stdio::null()).stderr(Stdio::null()).kill_on_drop(true).output();
    let output = match tokio::time::timeout(Duration::from_secs(10), run).await {
        Err(_) => return Err("wl-paste took too long".into()),
        Ok(Err(e)) if e.kind() == std::io::ErrorKind::NotFound => return Err(missing("wl-paste", "the wl-clipboard package")),
        Ok(Err(e)) => return Err(format!("Couldn't run wl-paste: {e}")),
        Ok(Ok(output)) => output,
    };
    if !output.status.success() {
        return Err("There's nothing on the clipboard (or it isn't text)".into());
    }
    let text = String::from_utf8(output.stdout).map_err(|_| "What's on the clipboard isn't text".to_string())?;
    if text.is_empty() {
        return Err("There's nothing on the clipboard".into());
    }
    if text.len() > MAX_CLIPBOARD {
        return Err("What's on the clipboard is too big to send".into());
    }
    Ok(text)
}

/// Opens an address that [`crate::links::check`] passed, with `xdg-open`.
///
/// As a systemd service, the opener is started through `systemd-run --user`, so the browser it
/// may start belongs to the session and not to this service (it would otherwise share the
/// service's read-only view of the home folder, and stop when the service does).
pub async fn open(address: &str) -> Result<(), String> {
    debug_assert!(!address.starts_with('-'));
    if address.starts_with('-') {
        return Err("That isn't a link".into());
    }
    if std::env::var_os("INVOCATION_ID").is_some() {
        let mut command = Command::new("systemd-run");
        command.args(["--user", "--quiet", "--collect", "--no-block"]);
        for name in ["WAYLAND_DISPLAY", "DISPLAY", "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE"] {
            let found = std::env::var(name).ok().or_else(|| desktop_environment().into_iter().find(|(n, _)| *n == name).map(|(_, v)| v));
            if let Some(value) = found {
                command.arg(format!("--setenv={name}={value}"));
            }
        }
        command.args(["--", "xdg-open", address]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        if let Ok(Ok(status)) = tokio::time::timeout(Duration::from_secs(10), command.status()).await {
            if status.success() {
                return Ok(());
            }
        }
    }
    let mut child = desktop_command("xdg-open")
        .arg(address)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| if e.kind() == std::io::ErrorKind::NotFound { missing("xdg-open", "the xdg-utils package") } else { format!("Couldn't run xdg-open: {e}") })?;
    // xdg-open may stay for as long as what it opened; give it a moment to fail, then let it be.
    match tokio::time::timeout(Duration::from_secs(3), child.wait()).await {
        Ok(Ok(status)) if !status.success() => Err("Nothing here could open that".into()),
        Ok(Err(e)) => Err(format!("Couldn't run xdg-open: {e}")),
        Ok(Ok(_)) => Ok(()),
        Err(_) => {
            tokio::spawn(async move {
                let _ = child.wait().await;
            });
            Ok(())
        }
    }
}

/// Whether an installed app handles links of this scheme (`xdg-mime`). When that can't be
/// asked, yes: `xdg-open` then says for itself.
pub async fn handles_scheme(scheme: &str) -> bool {
    let run = Command::new("xdg-mime").args(["query", "default", &format!("x-scheme-handler/{scheme}")]).stdin(Stdio::null()).stderr(Stdio::null()).kill_on_drop(true).output();
    match tokio::time::timeout(Duration::from_secs(5), run).await {
        Ok(Ok(output)) if output.status.success() => !String::from_utf8_lossy(&output.stdout).trim().is_empty(),
        _ => true,
    }
}

const SOUNDS: &[&str] = &[
    "/usr/share/sounds/freedesktop/stereo/phone-incoming-call.oga",
    "/usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga",
    "/usr/share/sounds/freedesktop/stereo/complete.oga",
    "/usr/share/sounds/freedesktop/stereo/bell.oga",
];

fn on_path(tool: &str) -> bool {
    std::env::var_os("PATH").is_some_and(|path| std::env::split_paths(&path).any(|dir| dir.join(tool).is_file()))
}

/// Plays the ringing sound over and over, until this is dropped (or for `how_long`). Whether
/// there was anything to play it with.
pub async fn ring(how_long: Duration) -> bool {
    let sound = std::env::var("SAM_ORCHESTRATOR_RING_SOUND").ok().or_else(|| SOUNDS.iter().find(|s| Path::new(s).is_file()).map(|s| s.to_string()));
    let player = ["pw-play", "paplay", "canberra-gtk-play"].into_iter().find(|p| on_path(p));
    let (Some(player), true) = (player, sound.is_some() || on_path("canberra-gtk-play")) else {
        tokio::time::sleep(how_long).await;
        return false;
    };
    let until = tokio::time::Instant::now() + how_long;
    while tokio::time::Instant::now() < until {
        let mut command = Command::new(player);
        match (player, &sound) {
            ("canberra-gtk-play", Some(sound)) => command.args(["-f", sound]),
            ("canberra-gtk-play", None) => command.args(["-i", "phone-incoming-call"]),
            (_, Some(sound)) => command.arg(sound),
            (_, None) => return false,
        };
        let started = tokio::time::Instant::now();
        // Dropped with this future (the ring was answered): the player stops mid-sound.
        let played = command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).kill_on_drop(true).status();
        let _ = tokio::time::timeout_at(until, played).await;
        // A player that fails at once (no sound server) mustn't spin.
        let pause = if started.elapsed() < Duration::from_millis(200) { 1500 } else { 400 };
        tokio::time::sleep(Duration::from_millis(pause)).await;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn host_names_are_tidied() {
        assert_eq!(prettify("maddies-laptop\n"), "Maddies laptop");
        assert_eq!(prettify("den_pc.lan"), "Den pc");
        assert_eq!(prettify("fedora"), "Fedora");
        assert_eq!(prettify(""), "Linux computer");
        assert_eq!(prettify("localhost"), "Linux computer");
        assert_eq!(prettify("--"), "Linux computer");
    }

    #[test]
    fn the_pretty_name_is_preferred() {
        assert_eq!(pretty_hostname("PRETTY_HOSTNAME=\"Maddie's Laptop\"\nCHASSIS=laptop\n").as_deref(), Some("Maddie's Laptop"));
        assert_eq!(pretty_hostname("CHASSIS=laptop\nPRETTY_HOSTNAME=Den\n").as_deref(), Some("Den"));
        assert_eq!(pretty_hostname("PRETTY_HOSTNAME='The \"big\" one'").as_deref(), Some("The \"big\" one"));
        assert_eq!(pretty_hostname("PRETTY_HOSTNAME=\"\"\n"), None);
        assert_eq!(pretty_hostname("CHASSIS=laptop\n"), None);
    }

    #[test]
    fn the_battery_is_read() {
        let dir = std::env::temp_dir().join(format!("samo-battery-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        assert_eq!(battery_at(&dir), None, "no such folder: no battery");
        let put = |device: &str, file: &str, text: &str| {
            fs::create_dir_all(dir.join(device)).unwrap();
            fs::write(dir.join(device).join(file), text).unwrap();
        };
        put("AC", "type", "Mains\n");
        put("AC", "online", "0\n");
        assert_eq!(battery_at(&dir), None, "a desktop: mains only");
        put("BAT0", "type", "Battery\n");
        put("BAT0", "capacity", "81\n");
        put("BAT0", "status", "Discharging\n");
        assert_eq!(battery_at(&dir), Some(Battery { level: 81, charging: false }));
        put("BAT0", "status", "Charging\n");
        assert_eq!(battery_at(&dir), Some(Battery { level: 81, charging: true }));
        // Full and plugged in reads "Full" or "Not charging": still on the charger.
        put("BAT0", "status", "Not charging\n");
        put("AC", "online", "1\n");
        assert_eq!(battery_at(&dir), Some(Battery { level: 81, charging: true }));
        // Two batteries: the average. A wireless mouse's battery isn't the computer's.
        put("BAT1", "type", "Battery\n");
        put("BAT1", "capacity", "40\n");
        put("hidpp_battery_0", "type", "Battery\n");
        put("hidpp_battery_0", "scope", "Device\n");
        put("hidpp_battery_0", "capacity", "5\n");
        put("AC", "online", "0\n");
        assert_eq!(battery_at(&dir), Some(Battery { level: 60, charging: false }));
        fs::remove_dir_all(dir).unwrap();
    }
}
