import { useState, type ReactNode } from "react";
import { Globe, LogOut, MonitorSmartphone } from "lucide-react";
import { api } from "../lib/api";
import { when } from "../lib/format";
import { PALETTES, usePrefs as useLook } from "../lib/prefs";
import { session, useSession } from "../lib/session";
import { Button, Field, IconButton, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";

function Panel({ title, children, help }: { title: string; children: ReactNode; help?: string }) {
  return (
    <section className="panel">
      <h2>{title}</h2>
      {help && <p className="muted">{help}</p>}
      {children}
    </section>
  );
}

export function Settings() {
  return (
    <div className="page settings">
      <PageHeader title="Settings" />
      <Panel title="Your account"><NameForm /><PasswordChange /></Panel>
      <Panel title="Signed in" help="Browsers and devices signed in to your account. Signing a device out here removes it from your devices too."><SignIns /></Panel>
      <Panel title="Appearance in this browser"><AppearanceSettings /></Panel>
    </div>
  );
}

function AppearanceSettings() {
  const boldText = useLook((p) => p.boldText);
  const palette = useLook((p) => p.palette);
  const scheme = useLook((p) => p.scheme);
  return (
    <div className="form-stack">
      <Field label="Theme">
        <div className="theme-picker" role="radiogroup" aria-label="Theme">
          {PALETTES.map((p) => (
            <button key={p.id} type="button" role="radio" aria-checked={palette === p.id} className={`theme-choice ${palette === p.id ? "is-selected" : ""}`} onClick={() => useLook.setState({ palette: p.id })}>
              <span className="theme-swatch" style={{ background: `linear-gradient(135deg, ${p.swatch[0]} 0 50%, ${p.swatch[1]} 50% 100%)` }} />
              {p.name}
            </button>
          ))}
        </div>
      </Field>
      <Field label="Style">
        <select value={scheme} onChange={(e) => useLook.setState({ scheme: e.target.value as "system" | "light" | "dark" })}>
          <option value="system">As the system is</option>
          <option value="light">Light</option>
          <option value="dark">Dark</option>
        </select>
      </Field>
      <Toggle label="Bold text" help="All text in Lexend Bold, the way a phone's Bold text setting draws it." checked={boldText} onChange={(boldText) => useLook.setState({ boldText })} />
    </div>
  );
}

function NameForm() {
  const user = useSession((s) => s.user)!;
  const [name, setName] = useState(user.displayName);
  const [busy, setBusy] = useState(false);
  return (
    <form className="inline-form" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try { session.setUser(await api.updateMe(name)); toast("Saved"); } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <Field label="Name" help={`Username: ${user.username}${user.role === "ADMIN" ? " · Admin" : ""}`}>
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
      </Field>
      <Button busy={busy} disabled={name.trim() === user.displayName} type="submit">Save</Button>
    </form>
  );
}

function PasswordChange() {
  const user = useSession((s) => s.user)!;
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  if (!user.hasPassword) return <p className="muted">You sign in through single sign-on.</p>;
  return (
    <form className="form-stack" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try {
        await api.changePassword(current, next);
        setCurrent("");
        setNext("");
        toast("Password changed. Other devices were signed out.");
      } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <h3>Change password</h3>
      <div className="two-col">
        <Field label="Current password"><input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required /></Field>
        <Field label="New password" help="At least 8 characters."><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} required /></Field>
      </div>
      <div><Button busy={busy} type="submit">Change password</Button></div>
    </form>
  );
}

function SignIns() {
  const { data, error, loading, setData } = useLoad(() => api.sessions(), []);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  const sorted = [...(data ?? [])].sort((a, b) => Number(b.current) - Number(a.current));
  return (
    <>
      <ul className="device-list">
        {sorted.map((s) => (
          <li key={s.id} className="device-row">
            {s.kind === "APP" ? <MonitorSmartphone size={20} /> : <Globe size={20} />}
            <div className="source-text">
              <div className="source-name">{s.name}{s.current && <span className="badge">This browser</span>}</div>
              <div className="source-sub">{s.kind === "APP" ? "A device" : "A browser"} · signed in {when(s.createdAt)} · last used {when(s.lastSeenAt)}</div>
            </div>
            {!s.current && (
              <IconButton label={`Sign out ${s.name}`} onClick={async () => {
                try { await api.revokeSession(s.id); setData((data ?? []).filter((x) => x.id !== s.id)); } catch (e) { errorToast(e); }
              }}><LogOut size={18} /></IconButton>
            )}
          </li>
        ))}
      </ul>
      <div className="button-row">
        <Button variant="ghost" onClick={() => void session.signOut()}><LogOut size={18} /> Sign out here</Button>
      </div>
    </>
  );
}
