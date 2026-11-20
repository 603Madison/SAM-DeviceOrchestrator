# Notes for Claude

## The project

SuperAwesomeMagicalOrchestrator ties the family together: one sign-in per device for every app,
and notifications, clipboard, links and "carry on here" between a person's devices.
`docs/PROTOCOL.md` is the contract between all the parts, here and in the other repositories:
**change it first, then every part that speaks it.**

- `shared/`: the API's models (kotlinx.serialization, `com.orchestrator.api`) and `Seal` (sealing
  for a device), used by the server and the Android app. The web's copies are in
  `web/src/lib/types.ts` and `web/src/lib/seal.ts`, the Linux agent's in `linux/src/`; keep them in
  step. All three open the test vector in PROTOCOL.md.
- `server/` (`com.orchestrator.server`): Kotlin + Ktor + SQLite, built like
  SuperAwesomeMagicalVideo's and Health's servers (auth, sessions, break-glass admin, OIDC with
  PKCE, `ApiError`, `Database.tx`; `auth/`, `util/` and `db/` are ported from them, and so are
  their tests). Migrations are in `db/Schema.kt`: never edit a released one, add another.
  - `devices/`: `DeviceStore` (a device is an app sign-in that registered a key; removing it ends
    the session), `MessageStore` (what's waiting, sealed), `Hub` (who's connected), `DeviceRoutes`
    (REST and the WebSocket at `/api/stream`).
  - `tickets/Tickets.kt`: signs tickets (ES256; the key is made at first use and kept sealed in
    `settings`).
  - `kit/OrchestratorTickets.kt`: what each of the family's servers uses to check a ticket. It's
    **copied into each of them** (Health, Music, Video, Maps, Email: `auth/OrchestratorTickets.kt`,
    only the package line differs); `KitTest` tests it against the real signer. Change it here,
    then copy it to all five.
- `kits/android/OrchestratorLink.kt`: how the family's Android apps reach the Android app here.
  Also copied into each; same rule.
- `web/`: React + Vite + TypeScript, built into the server's jar.
- `linux/`: the Linux agent (Rust): a user service owning `app.superawesomemagical.Orchestrator`
  on the session bus, and the `sam-orchestrator` command. SuperAwesomeMagicalLinux installs its
  .rpm and its Settings talk to it; Music's and Email's desktop apps ask it for tickets.
- `android/` (`com.orchestrator.app`, `app.superawesomemagical.orchestrator`): Jetpack Compose.
  The line (a foreground service), the notification listener, and the provider the other apps
  call. Use `attempt { }`, never `runCatching`, and rethrow `CancellationException` (as in Health).

## Promises to keep

- **The server never sees what devices send each other.** Everything between devices is a
  `Payload` sealed with `Seal` for the receiving device. Don't add a message kind the server
  reads, a plaintext field beside the sealed one, or a log line with content.
- **A ticket is the only thing shared with another server**, and only its public keys are
  published. Never the break-glass admin (here or there). A ticket's audience is the app
  server's address: never issue one for an address the admin didn't list.
- **Never mirror a mirrored notification** (no loops), nor a sensitive one (see the agents'
  filters, which have tests).
- **On Android an app gets a ticket only after the person approved it**, by package and signing
  certificate.
- **Links from other devices open only if they're http(s) or one of the family's own schemes.**
- No ads, no trackers: the web page talks to this server only (the CSP in `App.kt`).

## Design

Match the family (Music, Health, Video, Weather, Linux): Lexend, cool slate neutrals, rounded
cards (white in light themes), a soft aurora glow at the top, Aurora (purple and green) by default
with Violet, Ocean and Ember, light and dark, and a Bold text option. Copy is short, warm and
plain.

## Checks before pushing

```bash
cd web && npm ci && npm run build && npm test
```

```bash
./gradlew :shared:test :server:test :android:testDebugUnitTest :android:lintDebug :android:assembleDebug
```

```bash
cd linux && cargo test && cargo clippy --all-targets && tests/e2e.sh
```

Screenshot the web app (Playwright) after web changes.

## Releases

One version for everything: `versionName`/`versionCode` in `android/build.gradle.kts` (the server
and image read it) and the same `version` in `linux/Cargo.toml` (the release checks they match).
Bump them, write `release-notes/vX.Y.Z.md`, push, and run the **Release** workflow with
`version: vX.Y.Z`. It publishes the GitHub release with the Linux agent's packages
(`SuperAwesomeMagicalOrchestrator-Linux-vX.Y.Z-amd64.deb` and `-x86_64.rpm`: SuperAwesomeMagicalLinux's
image build follows the newest), pushes the image to
`ghcr.io/603madison/superawesomemagicalorchestrator`, and adds the signed APK
(`SuperAwesomeMagicalOrchestrator-vX.Y.Z.apk`).

The APK must always be signed with the same key (alias `orchestrator`). The key is not in git: CI
reads `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD` from repository secrets, local
builds read `keystore/release.properties` (git-ignored). Never generate a replacement for an
existing key. Until the secrets exist the release's `android` job fails (saying so) and the rest
still publishes. To make the first key (once, the owner):

```bash
keytool -genkeypair -v -keystore release.jks -alias orchestrator -keyalg RSA -keysize 4096 -validity 36500
```

```bash
base64 -w0 release.jks
```

## Building in a Claude cloud sandbox

- Maven Central answers HTTP 429: serve it from Google's mirror with an init script in
  `~/.gradle/init.d/` (see SuperAwesomeMagicalMusic's CLAUDE.md).
- Server-only Gradle runs: `SAMO_SERVER_ONLY=1`.
- Android SDK and Robolectric: as in Health's CLAUDE.md.
- Ktor's `testApplication` runs on a virtual clock and its WebSocket client stalls against the
  database's own dispatcher: test the live line over a real socket, as `StreamTest` does.
- There's no emulator and no desktop session: the Android app is checked by unit tests and lint
  only, the Linux agent by `linux/tests/e2e.sh` (two agents on their own session buses against a
  real server).
