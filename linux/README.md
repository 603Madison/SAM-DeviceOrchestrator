# SuperAwesomeMagicalOrchestrator on Linux

The agent for the Linux desktop: sign in once on this computer and every app of the family on
it is signed in, and this computer joins your other devices: notifications show on all of them,
the clipboard and links travel between them, they can ring each other, and each knows what
you're doing on the others.

It has no window. It's a user service on the session bus
(`app.superawesomemagical.Orchestrator`, started by D-Bus when something asks for it) and a
command, `sam-orchestrator`. [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) is the contract;
[`data/app.superawesomemagical.Orchestrator1.xml`](data/app.superawesomemagical.Orchestrator1.xml)
is the D-Bus interface apps and the desktop's shell code against.

## Using it

```sh
sam-orchestrator sign-in https://orchestrator.example.com   # opens your browser
sam-orchestrator status
sam-orchestrator devices
sam-orchestrator send-link "Pixel" https://example.com
sam-orchestrator send-clipboard            # to every other device; or name one
sam-orchestrator ring "Pixel"              # and: ring --stop "Pixel"
sam-orchestrator apps
sam-orchestrator ticket music              # a sign-in ticket, for scripts
sam-orchestrator activities                # what's happening on your other devices
sam-orchestrator settings                  # and: settings clipboard false
sam-orchestrator sign-out
```

Devices are named by name, the start of a name, or id. `--json` prints JSON where there's
something to print. Every command exits non-zero when it didn't work, with the reason in words.

A computer with no browser signs in with a device token on standard input
(`sam-orchestrator sign-in --token-stdin https://…`); one way to get a token is
`POST /api/auth/login` with `"app": true`.

`http://` servers are accepted with a warning: fine on a home network you trust, nowhere else.

### Settings

| | |
|---|---|
| `notificationsOut` | This computer's notifications go to your other devices (on) |
| `notificationsIn` | Your other devices' notifications show here (on) |
| `clipboard` | Clipboards sent here are taken (on) |
| `links` | Links sent here are opened (on) |
| `neverMirror` | Apps whose notifications never leave this computer, by name or desktop entry: `settings neverMirror "Signal, org.gnome.Calendar"` |

They're in `~/.config/superawesomemagical/orchestrator.json`, with the server's address. The
sign-in (the device's token and private key) is in
`~/.local/state/superawesomemagical/orchestrator/credentials.json`, readable only by you.

## What it does, and what it won't

- **Notifications out.** It watches the session bus as a monitor, so it works with whatever
  shows notifications (on SuperAwesomeMagicalLinux, the shell). Never mirrored: its own, ones
  that came from another device (the `x-sam-mirrored` hint), transient ones, empty ones, and
  the apps in `neverMirror`, and password managers and one-time-code apps whatever the settings
  say (`ALWAYS_PRIVATE` in `src/notes.rs`). One that updates many times a second is sent at most once every
  two seconds. Closing or dismissing one here removes it from the other devices; one that only
  times out here stays there.
- **Notifications in** are shown as "App · Device name", with the `x-sam-mirrored` hint.
  Dismissing one here removes it everywhere, the real one included.
- **Links** are opened only if they're `http://` or `https://` (with a site, without a
  username), or one of the family's own (`samvideo`, `sammusic`, `samemail`, `samaps`,
  `samhealth`: `src/links.rs`) with an installed app to handle it. Anything with spaces or
  control characters in it is refused. `xdg-open` is run directly, never through a shell, and
  only ever given an address that starts with its scheme.
- **The clipboard** goes through `wl-copy` and `wl-paste` (the `wl-clipboard` package). It's
  sent only when you say (`send-clipboard`); nothing watches it.
- **Ring** plays a sound over and over (`pw-play`, `paplay` or `canberra-gtk-play`) for a minute
  or until the "Found it" notification is dismissed.
- **Media** commands go to the MPRIS player that's playing, or the first there is.
- **Everything between devices is sealed** for the receiving device (P-256, HKDF-SHA256,
  AES-256-GCM: `src/seal.rs`), so the server passes it on without being able to read it. The
  log (`journalctl --user -u sam-orchestrator`) never carries what was sent.
- TLS is rustls with the system's certificates, so a private certificate authority installed on
  the computer is trusted; there's no OpenSSL in it. The live line doesn't go through an HTTP
  proxy.

## Building

Rust (the 2024 edition), and nothing else: no system libraries beyond glibc.

```sh
cargo build --release
cargo test && cargo clippy --all-targets
tests/e2e.sh
```

`tests/e2e.sh` runs the real server and two agents signed in as the same person, each on a
session bus of its own with a stand-in desktop, and checks that what one sends the other
opens. It needs `dbus-daemon`, `gdbus`, `curl`, `jq` and Java, and builds the server
(`SAMO_SERVER_ONLY=1 ./gradlew :server:installDist`) if it isn't built.

## Packages

```sh
cargo install cargo-deb cargo-generate-rpm
cargo build --release
cargo deb --no-build        # target/debian/sam-orchestrator_*.deb
cargo generate-rpm          # target/generate-rpm/sam-orchestrator-*.rpm
```

The packages install:

| | |
|---|---|
| `/usr/bin/sam-orchestrator` | the agent and the command |
| `/usr/lib/systemd/user/sam-orchestrator.service` | the user service (`Type=dbus`) |
| `/usr/share/dbus-1/services/app.superawesomemagical.Orchestrator.service` | so the session bus starts it when asked |
| `/usr/share/dbus-1/interfaces/app.superawesomemagical.Orchestrator1.xml` | the interface |
| `/usr/share/applications/app.superawesomemagical.Orchestrator.desktop` | the name its notifications go by (hidden from menus) |
| `/etc/xdg/autostart/sam-orchestrator.desktop` | at login: `sam-orchestrator autostart` |

**Starting at login.** `sam-orchestrator autostart` does nothing unless someone's signed in;
then it asks the session bus for the agent, which starts the systemd service. Desktops that run
XDG autostart entries (GNOME, KDE, anything with systemd's `xdg-desktop-autostart.target`) do
this by themselves. A bare Hyprland session doesn't: there, run `sam-orchestrator autostart`
from the session's start-up (SuperAwesomeMagicalLinux's `start-shell`). The unit has no
`WantedBy=graphical-session.target` because that target isn't reached in such a session either,
and a condition in the unit ("only when signed in") would also stop D-Bus from starting the
agent for the sign-in itself.

The RPM names Fedora's packages itself (`auto-req = "no"`), as the family's other desktop apps
do: it's built on Ubuntu.

## Layout

| | |
|---|---|
| `src/daemon.rs` | the agent: the D-Bus interface, the live line, what's sent and received |
| `src/monitor.rs` | watching the bus for notifications |
| `src/notes.rs` | which notifications are mirrored, markup, the two-second limit |
| `src/links.rs` | which links are opened |
| `src/seal.rs` | sealing (opens PROTOCOL.md's test vector) |
| `src/api.rs` | the server's API and the payloads, as `shared/…/Orchestrator.kt` has them |
| `src/signin.rs` | PKCE, the moment of listening on 127.0.0.1, the page the browser ends on |
| `src/store.rs` | the settings and the sign-in on disk |
| `src/system.rs` | this computer's name and battery; `xdg-open`, `wl-copy`, the ring |
| `src/cli.rs` | the command |
| `examples/fake-desktop.rs` | the stand-in notification daemon and media player `tests/e2e.sh` uses |
