import { Link, useLocation } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { LayoutGrid, LogOut, MonitorSmartphone, UserRound } from "lucide-react";
import { TABS, tabOf, type Tab } from "../lib/nav";
import { session, useSession } from "../lib/session";
import { IconButton } from "./ui";

/*
 * Getting around, as in the family's other apps. On a phone, the tabs along the bottom:
 * Devices · Apps · You. On a wide screen, a calm sidebar with the same three.
 */

const ICONS: Record<Tab, LucideIcon> = { devices: MonitorSmartphone, apps: LayoutGrid, you: UserRound };

/** The sidebar, on a wide screen. */
export function Sidebar() {
  const user = useSession((s) => s.user)!;
  const { pathname } = useLocation();
  const here = tabOf(pathname);
  return (
    <nav className="sidebar" aria-label="Main">
      <div className="brand">
        <img src="/icon.svg" alt="" width={32} height={32} />
        <span>SuperAwesome<wbr />Magical<wbr />Orchestrator</span>
      </div>
      <div className="nav-group">
        {TABS.map((t) => {
          const Icon = ICONS[t.id];
          const on = here === t.id;
          return (
            <Link key={t.id} to={t.to} className={`nav-link ${on ? "active" : ""}`} aria-current={on ? "page" : undefined}>
              <Icon size={20} aria-hidden="true" />
              <span className="nav-label">{t.label}</span>
            </Link>
          );
        })}
      </div>
      <div className="nav-spacer" />
      <p className="sidebar-promise">Sign in once. Sealed between your devices.</p>
      <div className="sidebar-user">
        <div className="sidebar-who">
          <span className="sidebar-name">{user.displayName}</span>
        </div>
        <IconButton label="Sign out" onClick={() => void session.signOut()}><LogOut size={18} /></IconButton>
      </div>
    </nav>
  );
}

/** The phone's tabs. */
export function TabBar() {
  const { pathname } = useLocation();
  const here = tabOf(pathname);
  return (
    <nav className="tabbar" aria-label="Tabs">
      {TABS.map((t) => {
        const Icon = ICONS[t.id];
        const on = here === t.id;
        return (
          <Link key={t.id} to={t.to} className={`tab ${on ? "is-on" : ""}`} aria-current={on ? "page" : undefined}>
            <span className="tab-icon"><Icon size={24} aria-hidden="true" /></span>
            <span className="tab-label">{t.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
