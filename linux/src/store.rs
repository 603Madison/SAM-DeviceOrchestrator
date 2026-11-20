//! What the agent keeps: settings in `$XDG_CONFIG_HOME/superawesomemagical/orchestrator.json`,
//! and the sign-in (token, this device's private key) in
//! `$XDG_STATE_HOME/superawesomemagical/orchestrator/credentials.json`, which only the person
//! can read.

use std::fs;
use std::io::Write;
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

fn yes() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    /// The orchestrator's address; empty until someone signs in.
    #[serde(default)]
    pub server: String,
    /// Mirror this computer's notifications to the other devices.
    #[serde(default = "yes")]
    pub notifications_out: bool,
    /// Show the other devices' notifications here.
    #[serde(default = "yes")]
    pub notifications_in: bool,
    /// Accept the clipboard from other devices.
    #[serde(default = "yes")]
    pub clipboard: bool,
    /// Open links sent here.
    #[serde(default = "yes")]
    pub links: bool,
    /// Apps whose notifications are never mirrored (by name or desktop entry).
    #[serde(default)]
    pub never_mirror: Vec<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: String::new(),
            notifications_out: true,
            notifications_in: true,
            clipboard: true,
            links: true,
            never_mirror: Vec::new(),
        }
    }
}

pub const SETTING_KEYS: &[&str] = &["notificationsOut", "notificationsIn", "clipboard", "links", "neverMirror"];

impl Config {
    /// The settings people change, as `Settings()` gives them.
    pub fn settings(&self) -> Value {
        json!({
            "notificationsOut": self.notifications_out,
            "notificationsIn": self.notifications_in,
            "clipboard": self.clipboard,
            "links": self.links,
            "neverMirror": self.never_mirror,
        })
    }

    /// Changes one setting. Toggles take true/false (on/off, yes/no); `neverMirror` takes a
    /// JSON list or names separated by commas.
    pub fn set(&mut self, key: &str, value: &str) -> Result<(), String> {
        let toggle = |value: &str| match value.trim().to_lowercase().as_str() {
            "true" | "on" | "yes" | "1" => Ok(true),
            "false" | "off" | "no" | "0" => Ok(false),
            _ => Err(format!("{key} is on or off: say true or false")),
        };
        match key {
            "notificationsOut" => self.notifications_out = toggle(value)?,
            "notificationsIn" => self.notifications_in = toggle(value)?,
            "clipboard" => self.clipboard = toggle(value)?,
            "links" => self.links = toggle(value)?,
            "neverMirror" => {
                let names: Vec<String> = if value.trim_start().starts_with('[') {
                    serde_json::from_str(value).map_err(|_| "neverMirror is a list of app names".to_string())?
                } else {
                    value.split(',').map(str::to_string).collect()
                };
                let mut tidy: Vec<String> = Vec::new();
                for name in names {
                    let name: String = name.trim().chars().filter(|c| !c.is_control()).take(100).collect();
                    if !name.is_empty() && !tidy.iter().any(|t| t.eq_ignore_ascii_case(&name)) {
                        tidy.push(name);
                    }
                }
                tidy.truncate(200);
                self.never_mirror = tidy;
            }
            _ => return Err(format!("There's no setting called {key}. There are: {}", SETTING_KEYS.join(", "))),
        }
        Ok(())
    }
}

/// The sign-in. The token and the private key are the secrets; the rest is who and what they're for.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Credentials {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    /// This device's P-256 private key (the 32-byte number, base64). Kept across sign-ins.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub private_key: Option<String>,
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub display_name: String,
}

impl Credentials {
    pub fn signed_in(&self) -> bool {
        self.token.as_deref().is_some_and(|t| !t.is_empty())
    }

    /// Signed out: the token and who it was go, the device's key stays.
    pub fn forget(&mut self) {
        *self = Credentials {
            private_key: self.private_key.take(),
            ..Default::default()
        };
    }
}

#[derive(Debug, Clone)]
pub struct Store {
    config_file: PathBuf,
    credentials_file: PathBuf,
}

fn base(variable: &str, fallback: &str) -> Result<PathBuf> {
    if let Some(dir) = std::env::var_os(variable).map(PathBuf::from).filter(|p| p.is_absolute()) {
        return Ok(dir);
    }
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .ok_or_else(|| anyhow!("No home folder (HOME isn't set)"))?;
    Ok(home.join(fallback))
}

impl Store {
    pub fn from_environment() -> Result<Self> {
        Ok(Self::at(
            &base("XDG_CONFIG_HOME", ".config")?,
            &base("XDG_STATE_HOME", ".local/state")?,
        ))
    }

    pub fn at(config_home: &Path, state_home: &Path) -> Self {
        Self {
            config_file: config_home.join("superawesomemagical").join("orchestrator.json"),
            credentials_file: state_home.join("superawesomemagical").join("orchestrator").join("credentials.json"),
        }
    }

    pub fn config(&self) -> Config {
        read(&self.config_file).unwrap_or_default()
    }

    pub fn credentials(&self) -> Credentials {
        read(&self.credentials_file).unwrap_or_default()
    }

    pub fn save_config(&self, config: &Config) -> Result<()> {
        let dir = self.config_file.parent().expect("the file is in a folder");
        fs::create_dir_all(dir).with_context(|| format!("Couldn't make {}", dir.display()))?;
        write_atomically(&self.config_file, &serde_json::to_vec_pretty(config)?, 0o644)
    }

    /// Writes the sign-in so only the person can read it: a 0700 folder, a 0600 file, and never
    /// a moment when the file is half-written or readable by anyone else.
    pub fn save_credentials(&self, credentials: &Credentials) -> Result<()> {
        let dir = self.credentials_file.parent().expect("the file is in a folder");
        fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(dir)
            .with_context(|| format!("Couldn't make {}", dir.display()))?;
        fs::set_permissions(dir, fs::Permissions::from_mode(0o700))
            .with_context(|| format!("Couldn't close {} to others", dir.display()))?;
        write_atomically(&self.credentials_file, &serde_json::to_vec_pretty(credentials)?, 0o600)
    }
}

fn read<T: for<'a> Deserialize<'a>>(file: &Path) -> Option<T> {
    serde_json::from_slice(&fs::read(file).ok()?).ok()
}

fn write_atomically(file: &Path, bytes: &[u8], mode: u32) -> Result<()> {
    let name = file.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    let temporary = file.with_file_name(format!(".{name}.{}.tmp", std::process::id()));
    let _ = fs::remove_file(&temporary);
    let result = (|| -> Result<()> {
        let mut out = fs::OpenOptions::new().write(true).create_new(true).mode(mode).open(&temporary)?;
        // The mode above is cut down by the umask, never widened; set it exactly.
        out.set_permissions(fs::Permissions::from_mode(mode))?;
        out.write_all(bytes)?;
        out.write_all(b"\n")?;
        out.sync_all()?;
        fs::rename(&temporary, file)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.with_context(|| format!("Couldn't write {}", file.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("samo-store-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn settings_default_to_on_and_survive_a_round_trip() {
        let config: Config = serde_json::from_str("{}").unwrap();
        assert_eq!(config, Config::default());
        assert!(config.notifications_out && config.notifications_in && config.clipboard && config.links && config.never_mirror.is_empty());
        let dir = scratch("config");
        let store = Store::at(&dir.join("config"), &dir.join("state"));
        assert_eq!(store.config(), Config::default(), "nothing saved yet");
        let mut changed = Config {
            server: "https://o.example.com".into(),
            ..Default::default()
        };
        changed.set("clipboard", "false").unwrap();
        changed.set("neverMirror", "Signal, , signal,Spotify").unwrap();
        store.save_config(&changed).unwrap();
        assert_eq!(store.config(), changed);
        let text = fs::read_to_string(dir.join("config/superawesomemagical/orchestrator.json")).unwrap();
        assert!(
            text.contains("\"notificationsOut\": true") && text.contains("\"neverMirror\""),
            "{text}"
        );
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn settings_are_changed_by_name() {
        let mut config = Config::default();
        for (key, value) in [
            ("notificationsOut", "off"),
            ("notificationsIn", "FALSE"),
            ("clipboard", "no"),
            ("links", "0"),
        ] {
            config.set(key, value).unwrap();
        }
        assert!(!config.notifications_out && !config.notifications_in && !config.clipboard && !config.links);
        config.set("links", "true").unwrap();
        assert!(config.links);
        config.set("neverMirror", r#"["Signal", "org.gnome.Calendar"]"#).unwrap();
        assert_eq!(config.never_mirror, ["Signal", "org.gnome.Calendar"]);
        config.set("neverMirror", "").unwrap();
        assert!(config.never_mirror.is_empty());
        assert!(config.set("links", "maybe").is_err());
        assert!(
            config.set("server", "https://elsewhere.example").is_err(),
            "the server changes by signing in, not here"
        );
        assert!(config.set("neverMirror", "[1, 2]").is_err());
        assert_eq!(config.settings()["links"], true);
    }

    #[test]
    fn the_sign_in_is_kept_where_only_the_person_can_read() {
        let dir = scratch("credentials");
        let store = Store::at(&dir.join("config"), &dir.join("state"));
        assert!(!store.credentials().signed_in());
        let credentials = Credentials {
            token: Some("t0ken".into()),
            private_key: Some("k3y".into()),
            device_id: "d1".into(),
            ..Default::default()
        };
        store.save_credentials(&credentials).unwrap();
        let folder = dir.join("state/superawesomemagical/orchestrator");
        assert_eq!(fs::metadata(&folder).unwrap().permissions().mode() & 0o777, 0o700);
        assert_eq!(
            fs::metadata(folder.join("credentials.json")).unwrap().permissions().mode() & 0o777,
            0o600
        );
        assert_eq!(store.credentials(), credentials);
        // Written again over a file and folder someone opened up: closed again, nothing left behind.
        fs::set_permissions(&folder, fs::Permissions::from_mode(0o755)).unwrap();
        fs::set_permissions(folder.join("credentials.json"), fs::Permissions::from_mode(0o644)).unwrap();
        let mut signed_out = credentials.clone();
        signed_out.forget();
        assert_eq!(
            signed_out,
            Credentials {
                private_key: Some("k3y".into()),
                ..Default::default()
            }
        );
        store.save_credentials(&signed_out).unwrap();
        assert_eq!(fs::metadata(&folder).unwrap().permissions().mode() & 0o777, 0o700);
        assert_eq!(
            fs::metadata(folder.join("credentials.json")).unwrap().permissions().mode() & 0o777,
            0o600
        );
        assert!(!store.credentials().signed_in());
        let left: Vec<_> = fs::read_dir(&folder).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(left, ["credentials.json"]);
        fs::remove_dir_all(dir).unwrap();
    }
}
