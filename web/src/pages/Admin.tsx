import { useEffect, useState } from "react";
import { Copy, KeyRound, LogOut, MoreHorizontal, Plus, Shield, ShieldOff, Trash2, UserCheck, UserX, X } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import { useSession } from "../lib/session";
import type { AdminSettings, AppLink, ServerSettings, User } from "../lib/types";
import { Button, errorToast, Field, IconButton, Menu, Modal, NumberInput, PageHeader, Spinner, toast, Toggle, useLoad } from "../components/ui";

export function Admin() {
  return (
    <div className="page settings">
      <PageHeader title="Admin" subtitle="People, sign-in, and the family's apps" />
      <section className="panel">
        <h2>People</h2>
        <Users />
      </section>
      <ServerPanels />
    </div>
  );
}

function Users() {
  const me = useSession((s) => s.user)!;
  const { data, error, loading, setData } = useLoad(() => api.users(), []);
  const [adding, setAdding] = useState(false);
  const [resetting, setResetting] = useState<User | null>(null);
  const [deleting, setDeleting] = useState<User | null>(null);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  const users = data ?? [];
  const replace = (u: User) => setData(users.map((x) => (x.id === u.id ? u : x)));
  const change = async (u: User, c: { role?: string; disabled?: boolean }) => {
    try { replace(await api.updateUser(u.id, c)); } catch (e) { errorToast(e); }
  };

  return (
    <>
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th>Name</th><th>Signs in with</th><th>Role</th><th>Last signed in</th><th /></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id} className={u.disabled ? "is-off" : ""}>
                <td>
                  <div className="source-name">{u.displayName}</div>
                  <div className="source-sub">{u.username}{u.breakGlass ? " · break-glass" : ""}{u.disabled ? " · turned off" : ""}</div>
                </td>
                <td>{u.oidc ? "Single sign-on" : "Password"}</td>
                <td><span className={`badge ${u.role === "ADMIN" ? "badge-accent" : ""}`}>{u.role === "ADMIN" ? "Admin" : "User"}</span></td>
                <td className="muted">{u.lastLoginAt ? when(u.lastLoginAt) : "Never"}</td>
                <td className="cell-actions">
                  {u.id !== me.id && !u.breakGlass && (
                    <Menu label={`More for ${u.username}`} trigger={<MoreHorizontal size={20} />} items={[
                      u.role === "ADMIN"
                        ? { label: "Make a user", icon: <ShieldOff size={18} />, onSelect: () => change(u, { role: "USER" }) }
                        : { label: "Make an admin", icon: <Shield size={18} />, onSelect: () => change(u, { role: "ADMIN" }) },
                      u.disabled
                        ? { label: "Turn on", icon: <UserCheck size={18} />, onSelect: () => change(u, { disabled: false }) }
                        : { label: "Turn off (and sign out)", icon: <UserX size={18} />, onSelect: () => change(u, { disabled: true }) },
                      ...(!u.oidc || u.hasPassword ? [{ label: "Set a password", icon: <KeyRound size={18} />, onSelect: () => setResetting(u) }] : []),
                      { label: "Sign out everywhere", icon: <LogOut size={18} />, onSelect: async () => { try { await api.signOutUser(u.id); toast(`Signed ${u.username} out`); } catch (e) { errorToast(e); } } },
                      { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: () => setDeleting(u) },
                    ]} />
                  )}
                  {u.breakGlass && u.id !== me.id && (
                    <Menu label={`More for ${u.username}`} trigger={<MoreHorizontal size={20} />} items={[
                      { label: "Set a password", icon: <KeyRound size={18} />, onSelect: () => setResetting(u) },
                    ]} />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Button onClick={() => setAdding(true)}><Plus size={18} /> Add a person</Button>
      <p className="muted">People who sign in through single sign-on get an account the first time (if that's on below).</p>
      {adding && <AddUser onClose={() => setAdding(false)} onAdded={(u) => { setData([...users, u]); setAdding(false); }} />}
      {resetting && <ResetPassword user={resetting} onClose={() => setResetting(null)} />}
      {deleting && (
        <Modal title={`Delete ${deleting.username}?`} onClose={() => setDeleting(null)} footer={<>
          <Button variant="ghost" onClick={() => setDeleting(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try { await api.deleteUser(deleting.id); setData(users.filter((x) => x.id !== deleting.id)); setDeleting(null); } catch (e) { errorToast(e); }
          }}>Delete</Button>
        </>}>
          <p>Their devices are signed out and removed with the account. To stop them signing in without removing anything, turn the account off instead.</p>
        </Modal>
      )}
    </>
  );
}

function AddUser({ onClose, onAdded }: { onClose: () => void; onAdded: (u: User) => void }) {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [admin, setAdmin] = useState(false);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { onAdded(await api.createUser(username.trim(), displayName.trim(), password, admin ? "ADMIN" : "USER")); }
    catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Add a person" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Add</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Username"><input autoFocus value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" required /></Field>
        <Field label="Name"><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></Field>
        <Field label="Password" help="At least 8 characters. It's temporary: they choose their own when they first sign in."><input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} required /></Field>
        <Toggle label="Admin" help="Admins manage people and these settings." checked={admin} onChange={setAdmin} />
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

function ResetPassword({ user, onClose }: { user: User; onClose: () => void }) {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { await api.resetPassword(user.id, password); toast(`Temporary password set for ${user.username}: they were signed out, and choose their own when they next sign in`); onClose(); }
    catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`New password for ${user.username}`} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Set password</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Password" help="At least 8 characters."><input autoFocus type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} /></Field>
      </form>
    </Modal>
  );
}

/** The family's apps by the id each calls itself, as the server knows them (Services.KNOWN_APPS). */
const KNOWN_APPS: Record<string, string> = {
  health: "SuperAwesomeMagicalHealth",
  music: "SuperAwesomeMagicalMusic",
  video: "SuperAwesomeMagicalVideo",
  maps: "SuperAwesomeMagicalMaps",
  email: "SuperAwesomeMagicalEmail",
};

function ServerPanels() {
  const { data, error, loading, setData } = useLoad(() => api.adminSettings(), []);
  if (loading && !data) return <section className="panel"><Spinner /></section>;
  if (error || !data) return <section className="panel"><div className="notice notice-error">{error}</div></section>;
  return <ServerForm initial={data} onSaved={setData} />;
}

function ServerForm({ initial, onSaved }: { initial: AdminSettings; onSaved: (s: AdminSettings) => void }) {
  const [settings, setSettings] = useState<ServerSettings>(initial.settings);
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  useEffect(() => { setSettings(initial.settings); }, [initial]);
  const env = new Set(initial.fromEnvironment);
  const oidc = settings.oidc;
  const setOidc = (change: Partial<typeof oidc>) => setSettings({ ...settings, oidc: { ...oidc, ...change } });
  const lockedNote = (key: string) => (env.has(key) ? ` Set by ${key}, which wins at every start.` : "");
  const apps = settings.apps;
  const setApps = (next: AppLink[]) => setSettings({ ...settings, apps: next });
  const setApp = (index: number, change: Partial<AppLink>) => setApps(apps.map((a, i) => (i === index ? { ...a, ...change } : a)));

  const save = async () => {
    setBusy(true);
    try {
      onSaved(await api.saveSettings(settings, secret || undefined));
      setSecret("");
      toast("Saved");
    } catch (e) { errorToast(e); }
    setBusy(false);
  };

  return (
    <>
      <section className="panel">
        <h2>Server</h2>
        <div className="form-stack">
          <Field label="Server name" help="Shown on the sign-in page."><input value={settings.serverName} onChange={(e) => setSettings({ ...settings, serverName: e.target.value })} /></Field>
          <Field label="Public address" help={`The address people reach this server at, e.g. https://orchestrator.example.com. The family's servers check sign-ins against it, and single sign-on behind a proxy needs it.${lockedNote("SAMO_PUBLIC_URL")}`}>
            <input value={settings.publicUrl} placeholder={window.location.origin} onChange={(e) => setSettings({ ...settings, publicUrl: e.target.value })} />
          </Field>
          <Toggle label="People can sign in with a password" help={`Off: everyone but the break-glass admin signs in through single sign-on.${lockedNote("SAMO_LOCAL_LOGIN")}`} checked={settings.localLogin} onChange={(localLogin) => setSettings({ ...settings, localLogin })} />
          <Field label="Keep undelivered things for" help="What's sent to a device that's away waits for it this long at most, sealed, then is dropped. Links wait up to a week, the clipboard ten minutes.">
            <span className="field-inline">
              <NumberInput inputMode="numeric" min={1} max={720} value={settings.keepHours} format={(n) => String(Math.round(n))}
                onChange={(n) => setSettings({ ...settings, keepHours: n == null ? 72 : Math.max(1, Math.min(720, Math.round(n))) })} aria-label="Hours" />
              <span className="muted">hours</span>
            </span>
          </Field>
        </div>
      </section>

      <section className="panel">
        <h2>Apps</h2>
        <p className="muted">
          The family's servers. A device signed in here is signed in to each of them, and they show on everyone's Apps page.
        </p>
        {env.has("SAMO_APPS") && <div className="notice notice-quiet">Set by SAMO_APPS, which wins at every start: change the list there.</div>}
        {apps.length > 0 && (
          <div className="app-rows">
            {apps.map((app, i) => (
              <div key={i} className="app-row">
                <Field label="Id"><input value={app.id} placeholder="music" autoCapitalize="none" spellCheck={false} onChange={(e) => setApp(i, { id: e.target.value })} /></Field>
                <Field label="Name"><input value={app.name} placeholder={KNOWN_APPS[app.id.trim().toLowerCase()] ?? "What people call it"} onChange={(e) => setApp(i, { name: e.target.value })} /></Field>
                <Field label="Address"><input value={app.url} placeholder={`https://${app.id.trim() || "app"}.example.com`} inputMode="url" autoCapitalize="none" spellCheck={false} onChange={(e) => setApp(i, { url: e.target.value })} /></Field>
                <IconButton type="button" label={`Take ${app.name || app.id || "this app"} off the list`} onClick={() => setApps(apps.filter((_, j) => j !== i))}><X size={18} /></IconButton>
              </div>
            ))}
          </div>
        )}
        <div className="quick-add">
          {Object.entries(KNOWN_APPS).filter(([id]) => !apps.some((a) => a.id.trim().toLowerCase() === id)).map(([id, name]) => (
            <button key={id} type="button" className="chip" onClick={() => setApps([...apps, { id, name, url: "" }])}><Plus size={16} /> {name.replace("SuperAwesomeMagical", "")}</button>
          ))}
          <button type="button" className="chip" onClick={() => setApps([...apps, { id: "", name: "", url: "" }])}><Plus size={16} /> Another</button>
        </div>
        <p className="muted">
          Each of those servers needs to know this one too: in its own Admin page, set the orchestrator's address to{" "}
          <code>{settings.publicUrl.trim() || window.location.origin}</code>. The address here must be the one that server calls its public address.
        </p>
      </section>

      <section className="panel">
        <h2>Single sign-on (OpenID Connect)</h2>
        <p className="muted">
          For Authentik: create an OAuth2/OpenID Provider (confidential client) and an Application for it, and add this
          redirect URI. Who may sign in is set in Authentik (bindings) and, optionally, with the user group below. Where the
          family's servers use the same Authentik, people are the same person on each.
        </p>
        <div className="copy-row">
          <code>{initial.redirectUri}</code>
          <Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(initial.redirectUri); toast("Copied"); }}><Copy size={16} /> Copy</Button>
        </div>
        <div className="form-stack">
          <Toggle label="Turn on single sign-on" checked={oidc.enabled} onChange={(enabled) => setOidc({ enabled })} />
          <Field label="Issuer" help={`Authentik: https://auth.example.com/application/o/<application slug>/${lockedNote("SAMO_OIDC_ISSUER")}`}>
            <input value={oidc.issuer} onChange={(e) => setOidc({ issuer: e.target.value })} placeholder="https://auth.example.com/application/o/orchestrator/" />
          </Field>
          <div className="two-col">
            <Field label="Client ID" help={lockedNote("SAMO_OIDC_CLIENT_ID").trim() || undefined}><input value={oidc.clientId} onChange={(e) => setOidc({ clientId: e.target.value })} /></Field>
            <Field label="Client secret" help={`${initial.oidcClientSecretSet ? "Saved. Leave empty to keep it." : "Not set."}${lockedNote("SAMO_OIDC_CLIENT_SECRET")}`}>
              <input type="password" autoComplete="new-password" value={secret} onChange={(e) => setSecret(e.target.value)} />
            </Field>
          </div>
          <div className="two-col">
            <Field label="Button label" help={lockedNote("SAMO_OIDC_BUTTON_LABEL").trim() || undefined}><input value={oidc.buttonLabel} onChange={(e) => setOidc({ buttonLabel: e.target.value })} /></Field>
            <Field label="Scopes" help={lockedNote("SAMO_OIDC_SCOPES").trim() || undefined}><input value={oidc.scopes} onChange={(e) => setOidc({ scopes: e.target.value })} /></Field>
          </div>
          <div className="two-col">
            <Field label="Group allowed to sign in" help={`Blank: anyone the provider lets through.${lockedNote("SAMO_OIDC_USER_GROUP")}`}><input value={oidc.userGroup} onChange={(e) => setOidc({ userGroup: e.target.value })} placeholder="family" /></Field>
            <Field label="Admin group" help={`Members are admins; everyone else is a user. Blank: set roles here.${lockedNote("SAMO_OIDC_ADMIN_GROUP")}`}><input value={oidc.adminGroup} onChange={(e) => setOidc({ adminGroup: e.target.value })} placeholder="family-admins" /></Field>
          </div>
          <div className="two-col">
            <Field label="Username claim"><input value={oidc.usernameClaim} onChange={(e) => setOidc({ usernameClaim: e.target.value })} /></Field>
            <Field label="Groups claim"><input value={oidc.groupsClaim} onChange={(e) => setOidc({ groupsClaim: e.target.value })} /></Field>
          </div>
          <Toggle label="Make an account on first sign-in" help="Off: an admin adds people first." checked={oidc.autoCreateUsers} onChange={(autoCreateUsers) => setOidc({ autoCreateUsers })} />
          {testResult && <div className={`notice ${testResult.ok ? "notice-ok" : "notice-error"}`}>{testResult.message}</div>}
          <div className="button-row">
            <Button busy={testing} disabled={!oidc.issuer} onClick={async () => {
              setTesting(true);
              try { setTestResult(await api.testOidc(oidc)); } catch (e) { errorToast(e); }
              setTesting(false);
            }}>Check the issuer</Button>
          </div>
        </div>
      </section>

      <div className="save-bar">
        <span className="muted">Server {initial.version}</span>
        <Button variant="primary" busy={busy} onClick={save}>Save server settings</Button>
      </div>
    </>
  );
}
