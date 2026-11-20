# SuperAwesomeMagicalOrchestrator

Sign in once on each of your devices, and everything of yours is signed in and in step.

It's what makes the SuperAwesomeMagical family (Linux, Music, Health, Video, Maps, Email, Browser)
feel like one thing:

- **One sign-in for everything.** Sign in to the orchestrator on your laptop or phone (with
  **Authentik**, or a password), and SuperAwesomeMagicalMusic, Health, Video, Maps and Email on
  that device open already signed in, pointed at your own servers. No addresses to type.
- **Notifications everywhere.** What pings your phone shows on your laptop, and the other way
  round. Dismiss it on one and it goes from the others.
- **Clipboard and links.** Send what you copied, or the page you're on, to another device.
- **Carry on where you were.** The song, video, page or route open on one device is offered on
  the others.
- **Find it.** Ring a phone that's under a cushion.
- **Your devices.** Which are connected, their batteries, and a way to sign one out from
  anywhere if it's lost.

What your devices send each other is **sealed for the receiving device before it leaves the
sending one**: the server passes it on and keeps it until it's delivered, and can't read it.
Nobody's notifications or clipboard are in the database or the logs, and an admin can't see them.

## The parts

- **Server** (`server/`): Kotlin and Ktor with SQLite, in one container for Podman or Docker.
  Accounts (Authentik or any OpenID Connect provider, with a local **break-glass admin** that
  always works), each person's devices, the list of your apps' servers, and the relay.
- **Web page** (`web/`): your devices (send a link, ring one, remove one), your apps, and Admin.
- **Linux agent** (`linux/`): a small background service and the `sam-orchestrator` command. It's
  part of SuperAwesomeMagicalLinux (Settings › Account) and installs on any Linux desktop from the
  `.deb` or `.rpm`.
- **Android app** (`android/`): the same for phones, and the way the family's Android apps sign
  in.
- **Shared models** (`shared/`) and the two small files each of the family's apps carries
  (`kits/`, and `server/…/kit/`).

[docs/PROTOCOL.md](docs/PROTOCOL.md) says exactly how they talk; [docs/API.md](docs/API.md) lists
the server's endpoints.

## How signing in to an app works

1. You're signed in to the orchestrator on the device.
2. An app (say Music) starts, and asks the orchestrator on the device for a **ticket**: a signed
   note from your orchestrator server saying who's signed in, made for your Music server only,
   good for two minutes and for one use.
3. The app hands the ticket to your Music server, which checks it against the orchestrator's
   published keys and signs the app in as you.

Your Music server finds your account the same way it does for Authentik (it's the same you), so
nothing needs linking. Each of your servers only needs to be told where the orchestrator is; the
orchestrator needs to know where each of them is. Nothing else is shared between them: no
passwords, no tokens.

On Android, the first time an app asks, you're asked once whether it may sign in as you.

## Running it

The image is `ghcr.io/603madison/superawesomemagicalorchestrator`. Everything it keeps is in
`/data`.

### Podman (Quadlet, the systemd way, shows in Cockpit)

```bash
mkdir -p ~/.config/containers/systemd
cp deploy/superawesomemagicalorchestrator.container ~/.config/containers/systemd/
systemctl --user daemon-reload && systemctl --user start superawesomemagicalorchestrator
```

### Compose

```bash
cd deploy && podman compose up -d      # or: docker compose up -d
```

The first start prints the break-glass admin's password once on the console. Open
`http://<server>:8097`, sign in as `admin`, and set things up on the Admin page.

Put it behind HTTPS (a reverse proxy) before signing devices in from outside your home: the
sign-in page and every device's line go through it. WebSockets must be allowed through
(`/api/stream`).

### Setting it up

1. **Public address**: where people and devices reach it, e.g. `https://orchestrator.example.com`.
   It's written into every ticket, so set it before linking apps.
2. **Authentik**: make an OAuth2/OpenID provider and application for it (redirect URI: shown on
   the Admin page), and enter the issuer, client ID and secret. Use the same Authentik your other
   servers use: that's how each of them knows you're you.
3. **Apps**: list each of your servers (Music at `https://music.example.com`, and so on).
4. **On each of those servers**, in its own Admin page, enter this orchestrator's address
   (or set `SAMM_…`/`SAMH_ORCHESTRATOR_URL` and friends; each README says which).
5. **On each device**: SuperAwesomeMagicalLinux: Settings › Account. Any other Linux: install the
   package and run `sam-orchestrator sign-in https://orchestrator.example.com`. Android: install
   the app and sign in.

### Settings from the environment

Everything is on the Admin page; these win at every start, for configuration as code.

| | |
|---|---|
| `SAMO_PUBLIC_URL` | The address people and devices use. |
| `SAMO_APPS` | `music=https://music.example.com,health=https://health.example.com,…` |
| `SAMO_OIDC_ISSUER`, `SAMO_OIDC_CLIENT_ID`, `SAMO_OIDC_CLIENT_SECRET` | Single sign-on. |
| `SAMO_OIDC_BUTTON_LABEL`, `SAMO_OIDC_SCOPES` | |
| `SAMO_OIDC_USER_GROUP`, `SAMO_OIDC_ADMIN_GROUP` | Who may sign in; who is an admin. |
| `SAMO_LOCAL_LOGIN` | `false`: only the break-glass admin signs in with a password. |
| `SAMO_ADMIN_USERNAME`, `SAMO_ADMIN_PASSWORD` | The break-glass admin, for a new server. |
| `SAMO_TRUST_PROXY`, `SAMO_PROXIES`, `SAMO_CLIENT_IP_HEADER` | Behind a reverse proxy. |
| `SAMO_DATA_DIR`, `SAMO_HOST`, `SAMO_PORT` | Where it keeps things and listens (`/data`, `0.0.0.0`, `8080`). |

Lost the break-glass password: `samo-server reset-admin-password` in the container.

## What it trusts, and what it doesn't

- **The server is the authority on who you are** for the apps that are told to trust it, as
  Authentik is. Whoever runs it could sign in to those apps as anyone: run it yourself, like the
  rest.
- **The server can't read what devices send each other.** It does see which of your devices sent
  something to which, when, and how big it was; and device names, kinds and battery levels.
- **Sealing doesn't prove who sent something**: the server says which device did. A server gone
  bad could send a device a link or a ring it made up; it couldn't read your notifications.
- **A ticket is for one server, once, for two minutes.** One app's server can't use it on another.
- **The break-glass admin never gets tickets**, and no ticket ever signs in to another server's
  break-glass admin.
- On a phone, **an app gets tickets only after you've said yes to it**, and the answer is tied to
  the app's signing certificate. On a Linux desktop, anything running as you can ask, as it could
  read your browser's cookies: the session is the boundary there.

## Building

```bash
cd web && npm ci && npm run build && npm test
./gradlew :shared:test :server:test :android:testDebugUnitTest :android:lintDebug :android:assembleDebug
cd linux && cargo test && cargo clippy --all-targets
```

GPL-3.0-only.
