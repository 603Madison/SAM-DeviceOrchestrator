import { Lock, LogOut, Settings as SettingsIcon, Shield } from "lucide-react";
import { session, useSession } from "../lib/session";
import { MenuGroup, MenuRow, PageHeader } from "../components/ui";

/** The person's own corner: settings, and for admins, the server. */
export function You() {
  const user = useSession((s) => s.user)!;
  const config = useSession((s) => s.config);
  return (
    <div className="page you-page">
      <PageHeader title="You" subtitle={`${user.displayName} · ${user.username}`} />
      <section className="promise panel-card">
        <span className="promise-icon"><Lock size={22} aria-hidden="true" /></span>
        <div>
          <h2>Between your devices only</h2>
          <p className="muted">
            Notifications, the clipboard and links are sealed on the device that sends them and opened on the one that gets them.{" "}
            {config?.serverName ?? "Your server"} passes them along and can't read them.
          </p>
        </div>
      </section>
      <MenuGroup>
        <MenuRow to="/settings" icon={<SettingsIcon size={20} />} title="Settings" sub="Your name and password, where you're signed in, and how it looks" />
        {user.role === "ADMIN" && <MenuRow to="/admin" icon={<Shield size={20} />} title="Admin" sub="People, sign-in, and the family's apps" />}
      </MenuGroup>
      <button type="button" className="btn btn-ghost" onClick={() => void session.signOut()}><LogOut size={18} /> Sign out</button>
      {config && <p className="end-note">Server {config.version}</p>}
    </div>
  );
}
