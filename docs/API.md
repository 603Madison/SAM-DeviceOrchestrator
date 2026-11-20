# The server's API

Everything is JSON under `/api`. Browsers sign in with a cookie (`samo_session`) and send
`X-SAMO: 1` with every change; devices send `Authorization: Bearer <token>`. Errors are
`{"error": "words for the person"}`. The models are in `shared/` (Kotlin) and
`web/src/lib/types.ts`.

What devices do (registering, the live line, sending, tickets) is described in full, with the
sealing scheme and what the family's servers do with a ticket, in [PROTOCOL.md](PROTOCOL.md).

## Signing in

| | |
|---|---|
| `GET /api/auth/config` | The server's name and version, and which ways of signing in it offers. |
| `POST /api/auth/login` `{username, password, app?, deviceName?}` | A cookie; or, with `app`, a token. |
| `POST /api/auth/logout` | |
| `GET /api/auth/oidc/start?return=/…`, `GET /api/auth/oidc/callback` | Single sign-on (Authentik): OIDC code flow with PKCE. |
| `POST /api/auth/app/code` `{challenge}` | From the signed-in web page (`/connect-app`): a one-time code for a device. |
| `POST /api/auth/app/token` `{code, verifier, deviceName}` | The device swaps the code for its token. |
| `GET /api/me`, `PATCH /api/me` `{displayName}`, `POST /api/me/password` `{current, new}` | |
| `GET /api/me/sessions`, `DELETE /api/me/sessions/{id}` | Signed-in browsers and devices. |

## Devices, apps and messages

| | |
|---|---|
| `GET /api/devices` | The person's devices. |
| `POST /api/devices` | A signed-in device registers, or updates its name and key. |
| `GET /api/devices/me`, `POST /api/devices/me/status` | |
| `PATCH /api/devices/{id}` `{name}`, `DELETE /api/devices/{id}` | Rename; remove (signs it out). |
| `GET /api/apps` | The family's servers, as the admin listed them. |
| `POST /api/tickets` `{app}` | A sign-in ticket for that app's server (devices only). |
| `POST /api/messages` | Sends something sealed to some of the person's devices (a device, or the web page). |
| `GET /api/messages`, `POST /api/messages/ack` `{ids}` | What's waiting for this device. |
| `GET /api/stream` | WebSocket: the device's live line. |

## For the family's servers (no sign-in)

| | |
|---|---|
| `GET /api/orchestrator` | `{name, version, issuer, jwksUri, apps}`. |
| `GET /api/orchestrator/keys` | The public keys tickets are signed with (a JWK set). |
| `GET /api/health` | `{ok, version}`. |

## Admin

| | |
|---|---|
| `GET/POST /api/admin/users`, `PATCH/DELETE /api/admin/users/{id}` | Accounts. Admins manage accounts; they can't read what devices send each other (nobody but the devices can). |
| `POST /api/admin/users/{id}/password`, `DELETE /api/admin/users/{id}/sessions` | A temporary password; sign someone out everywhere. |
| `GET/PUT /api/admin/settings` | Name, public address, single sign-on, the list of apps, how long undelivered things are kept. |
| `POST /api/admin/oidc/test` | Whether the identity provider answers. |
