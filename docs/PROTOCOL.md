# How it fits together

SuperAwesomeMagicalOrchestrator has three kinds of parts:

- **The server** (this repository's `server/`): who people are (Authentik, or passwords), which
  devices each has, the list of the family's apps, sign-in tickets, and a relay that passes
  sealed messages between a person's devices.
- **An agent on each device**: the Linux agent (`linux/`, a user service with a D-Bus name) and
  the Android app (`android/`). It holds the device's sign-in and key, keeps a line open to the
  server, mirrors notifications, and answers the apps on the device.
- **The apps and their servers**: each asks the agent on its device for a ticket and hands it to
  its own server, which checks it against the orchestrator's published keys.

What needs the server and what doesn't:

| | Where it happens |
|---|---|
| Signing an app in | The app asks the agent (local); the agent asks the server for a ticket; the app's server checks it. One round trip each. |
| Notifications, clipboard, links, ring, "carry on here" | Sealed on the sending device, relayed by the server, opened on the receiving device. The server never sees the words. |
| Watching and dismissing notifications, opening links, setting the clipboard | On the device, by the agent. |
| Which devices exist, which are connected, battery | The server (not sealed: names, kinds, battery level). |

## Signing in a device

Like the family's apps: the agent opens a browser at
`<server>/connect-app?challenge=<PKCE S256 challenge>&name=<device name>&redirect=<where>`.
Before opening it, the agent shows a **pairing code**, and the page shows the same one, so the
person can see the page is for the sign-in they started: six digits from the challenge
(`n = 0`; for each character, `n = (n * 64 + its index in A–Z a–z 0–9 - _) mod 1000000`; shown
as `123 456`; the challenge `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM` gives `249 228`).
The person signs in (Authentik or a password) and agrees; the page sends the browser to
`<redirect>?code=<one-time code>`. `redirect` may only be `samorchestrator://signed-in` (Android)
or `http://127.0.0.1:<port>/…` / `http://localhost:<port>/…` (the Linux agent listens there for a
moment). The agent swaps the code for its token:

    POST /api/auth/app/token   {"code": "...", "verifier": "...", "deviceName": "..."}
    -> {"user": {...}, "token": "..."}

Then it registers (and again whenever its name or key changes):

    POST /api/devices   Authorization: Bearer <token>
    {"name": "Maddie's laptop", "kind": "LINUX" | "ANDROID" | "OTHER", "model": "ThinkPad X1",
     "publicKey": "<base64>", "capabilities": ["notifications", "clipboard", "links", "ring", "activity", "media"]}

## The API (devices)

All with `Authorization: Bearer <token>`; JSON; errors are `{"error": "words for the person"}`.

| | |
|---|---|
| `GET /api/devices` | The person's devices: `id, name, kind, model, publicKey, capabilities, online, lastSeenAt, battery {level, charging}, current`. |
| `PATCH /api/devices/{id}` `{"name"}` | Rename. |
| `DELETE /api/devices/{id}` | Remove: signs that device out. |
| `POST /api/devices/me/status` `{"battery": {"level": 81, "charging": true}}` | For a device without a line open. |
| `GET /api/apps` | The family's servers: `[{"id": "music", "name": "SuperAwesomeMagicalMusic", "url": "https://music.example.com"}]`. |
| `POST /api/tickets` `{"app": "music"}` | `{"app", "url", "ticket", "expiresAt"}`. 404 when the admin hasn't listed that app. |
| `POST /api/messages` | Send (below). |
| `GET /api/messages`, `POST /api/messages/ack` `{"ids": []}` | What's waiting, for a device that can't keep a line open. |
| `GET /api/stream` (WebSocket) | The live line (below). |

### The live line

A WebSocket at `/api/stream` with the same `Authorization` header. JSON text frames, each with a
`type`:

- server → device `{"type":"message","message":{"id","from","fromName","sentAt","sealed":{…}}}`:
  something for this device. What was waiting comes first, on connecting. Answer
  `{"type":"ack","ids":["…"]}` once it's dealt with; until then the server keeps it and sends it
  again on the next connection (so handle a repeat: notifications by `key`, the rest by `id`).
- server → device `{"type":"devices","devices":[…]}`: the list changed.
- device → server `{"type":"status","battery":{"level":81,"charging":true}}`.
- either way `{"type":"ping"}`.

The server closes the line when the device is removed. Reconnect with a growing pause (1 s to
5 min). A line refused for not being signed in is closed with code 1008 straight after opening
(a WebSocket can't answer 401): ask `GET /api/devices/me`, and a 401 there means signed out:
forget the token and say so.

### Sending

    POST /api/messages
    {"to": {"<device id>": {"epk": "...", "nonce": "...", "ct": "..."}, ...},
     "ttlSeconds": 86400, "collapseKey": "clipboard"}
    -> {"delivered": ["<ids connected now>"], "queued": ["<ids it's waiting for>"]}

One sealed copy for each receiving device. `collapseKey`: a newer one replaces an undelivered
older one from the same sender (use `"clipboard"`, `"ring"`, `"activity:<app>"`; notifications:
none).
`ttlSeconds`: notifications 86400, dismissals 86400, clipboard 600, links 604800, ring 300,
activity 900, media 60.

## Sealing

Each device has a P-256 key pair; its public key is the 65-byte uncompressed point
(`04 || X || Y`), base64 (standard alphabet, padded). To seal `plain` for a device:

1. Make a one-time P-256 key pair; `epk` is its public point, same encoding.
2. `shared` = ECDH(one-time private, device public): the 32-byte X coordinate.
3. `key` = HKDF-SHA256(ikm = `shared`, salt = `epk point (65 bytes) || device public point (65 bytes)`,
   info = `"samo/v1/seal"`), 32 bytes.
4. `ct` = AES-256-GCM(key, nonce = 12 random bytes, associated data = the device's id as UTF-8),
   ciphertext then the 16-byte tag. `nonce` and `ct` base64.

Test vector (every client's tests open it):

    device id    device-1
    private key  fPVfWTT+lmpkhYZJbYmgJjnb2Yk2z5N40jybPxGus/g=          (the 32-byte number)
    public key   BLjLkiLeA9LQO1HSvascpiMSjqDzL48RyznPjsAfHRkwvfHZWmiCDUIt1/tIu0HcMP5IJjCNMrVxG2v6lxnHJic=
    epk          BCb8iyUOM3J9h1vLpRI9Fb3lmP+DrST3xeOkuCKM5hRqMMj7XwcscLCPmJQH1FOH5SM9QZNPDYbkJI6ZTey43pQ=
    nonce        od4tYckzNodlv4sX
    ct           wy4elJA1eGU8oFGPEuH4GkjWfSz0kD/j2Eq6oP97idy//cg4UoNeWX//frfkREIWSOpTXNp+tYXjJqaMcXGOB+hiqw==
    plain        {"type":"clipboard","text":"hello from the aurora"}

Reject a public key that isn't a point on the curve. Sealing keeps content out of the server's
database and logs; it doesn't prove who sent it (the server says which device did).

## What's inside (payloads)

JSON, with a `type`. Skip types you don't know.

| type | fields | meaning |
|---|---|---|
| `notification` | `key, app, appId, title, text, at, icon?` (icon: PNG, base64, 64 px at most, 8 KB at most) | Show this notification here too. `key` is stable on the sender. Show it marked with the sending device's name. |
| `dismiss` | `key` | It was dismissed there: remove it here. Send this too when the person dismisses a mirrored one here, to every other device (the origin then removes the real one). |
| `clipboard` | `text` | Put it on the clipboard. |
| `link` | `url, title` | Open it (http/https open in the browser; other schemes only if an installed app of the family handles them). |
| `ring` | `on` | Make a noise until found, or stop. |
| `activity` | `app, title?, detail, url, at` | What the person is doing in an app there. Null title: stopped. Keep the latest for each device and app; forget after 15 minutes. |
| `media` | `action`: `play, pause, toggle, next, previous` | Control what's playing here. |

Never mirror a notification that was itself mirrored (no loops), nor the agent's own.

## Sign-in tickets

`POST /api/tickets {"app": "music"}` gives a JWT: header `{"alg":"ES256","kid":…,"typ":"samo-ticket+jwt"}`,
claims `iss` (the orchestrator's public address), `aud` (the app server's address as the admin
listed it), `app`, `sub` (the orchestrator's user id), `preferred_username`, `name`, `email?`,
`oidc_iss?`, `oidc_sub?` (who they are at the identity provider), `groups`, `device_id`,
`device_name`, `jti`, `iat`, `nbf`, `exp` (two minutes). Keys: `GET /api/orchestrator/keys` (a
JWK set). `GET /api/orchestrator` says `{name, version, issuer, jwksUri, apps}`.

### What each of the family's servers does

- A setting, the orchestrator's address (Admin page; `<PREFIX>_ORCHESTRATOR_URL`). Blank: none of
  this exists (`404`).
- `GET /api/auth/config` gains `"orchestrator": true` when it's set.
- `POST /api/auth/orchestrator {"ticket": "...", "deviceName": "..."}` → what an app sign-in
  returns (the user and an app token). The ticket is checked with `OrchestratorTickets`
  (`server/src/main/kotlin/com/orchestrator/server/kit/`, copied in): signature, issuer, audience
  (this server's public address, or the address the request came to), app, dates, used once.
- Whose account:
  1. The ticket has `oidc_sub`, and this server uses the same identity provider (same scheme,
     host and port as its own OIDC issuer; `OrchestratorTickets.sameProvider`): the account with
     that subject under this server's issuer. None yet: the same rules as the server's own
     single sign-on (its allowed group, its admin group, claiming an account an admin made
     ahead, making one if that's on), with the ticket's `groups`, name and email.
  2. Otherwise, the account with the ticket's username, if it's an ordinary one: not the
     break-glass admin, not disabled, and not one tied to an identity provider.
  3. Otherwise refused (401, "There's no account for you on this server yet").
  Never the break-glass admin. A disabled account stays refused. An admin-set temporary password
  still has to be changed as usual.
- Refusals are logged with the reason and counted against the address like wrong passwords.

## On the device: how apps reach the agent

### Linux (D-Bus, session bus)

Name `app.superawesomemagical.Orchestrator`, path `/app/superawesomemagical/Orchestrator`,
interface `app.superawesomemagical.Orchestrator1`. The service is D-Bus activated.

| Method | |
|---|---|
| `Status() → s` | JSON `{"signedIn", "server", "username", "displayName", "connected", "device": {"id","name"}}`. |
| `SignInTo(s app) → (s url, s ticket)` | A ticket for that app. Errors: `app.superawesomemagical.Orchestrator.Error.NotSignedIn`, `.UnknownApp`, `.Failed`. |
| `Apps() → s` | JSON, as `GET /api/apps`. |
| `Devices() → s` | JSON, as `GET /api/devices`. |
| `Send(s device, s payload)` | Seals the payload JSON for a device id (`""`: every other device). |
| `PublishActivity(s app, s title, s detail, s url)` | Empty title: stopped. |
| `Activities() → s` | JSON `[{"device","deviceName","app","title","detail","url","at"}]`: what's happening on the other devices. |
| `SignIn(s server)`, `SignOut()` | |

Signals: `Changed()` (status or devices), `ActivitiesChanged()`.

The same from a shell: `sam-orchestrator status|devices|apps|sign-in <server>|sign-out|ticket <app>|send-link <device> <url>|send-clipboard [device]|ring <device>` (`--json` where it prints).

### Android (a content provider)

Authority `app.superawesomemagical.orchestrator.link`; `ContentResolver.call(uri, method, arg, extras)`.
`kits/android/OrchestratorLink.kt` wraps it.

| method | arg, extras | answer (Bundle) |
|---|---|---|
| `status` | | `signedIn` (boolean), `server`, `username`, `displayName` |
| `signIn` | arg: the app id | `ok` + `url` + `ticket`; or `needsApproval` (true); or `error` |
| `publishActivity` | arg: the app id; extras `title?`, `detail`, `url` | `ok` |

The first time an app asks, the person is asked once whether that app may sign in as them: the
app starts `app.superawesomemagical.orchestrator.APPROVE` (extra `app`) for a result and asks
again on `RESULT_OK`. The agent remembers the app's package and signing certificate; a different
certificate under the same package is a different app and is asked about again.

## What an app does

1. Not signed in, at start: ask the agent for a ticket (quietly; no agent or nobody signed in:
   show the usual sign-in). With a ticket: `POST <url>/api/auth/orchestrator`, keep the token and
   the server address as if the person had signed in by hand. Any failure: the usual sign-in,
   with no error shown.
2. The sign-in screen offers "Continue as <name>" when the agent has someone signed in.
3. Signing out of the app stays signed out (don't sign straight back in: remember the person
   chose to, until they sign in again).
4. Where it makes sense, say what the person is doing (`PublishActivity`), and open what other
   devices send (the app's own links).
