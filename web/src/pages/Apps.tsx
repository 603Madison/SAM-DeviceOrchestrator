import type { LucideIcon } from "lucide-react";
import { AppWindow, ChevronRight, Clapperboard, HeartPulse, LayoutGrid, Mail, Map, Music } from "lucide-react";
import { api } from "../lib/api";
import { useSession } from "../lib/session";
import type { AppLink } from "../lib/types";
import { Breakable, Empty, ErrorNotice, PageHeader, Spinner, useLoad } from "../components/ui";

const ICONS: Record<string, LucideIcon> = { health: HeartPulse, music: Music, video: Clapperboard, maps: Map, email: Mail };

/** An address to show: "music.example.com". */
function host(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

/** Only web addresses become links (the server checks them too). */
function isWeb(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

/** The family's apps the admin listed, each a link to its server. */
export function Apps() {
  const user = useSession((s) => s.user)!;
  const { data, error, loading, reload } = useLoad(() => api.apps(), []);
  const apps = (data ?? []).filter((a) => isWeb(a.url));
  return (
    <div className="page apps-page">
      <PageHeader title="Apps" subtitle={apps.length > 0 ? "On a device you've signed in, these open already signed in as you." : undefined} />
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {loading && !data && <div className="center-pad"><Spinner size={28} /></div>}
      {data && apps.length === 0 && (
        <section className="panel-card">
          <Empty icon={<LayoutGrid />} title="No apps listed yet">
            <p>
              {user.role === "ADMIN"
                ? "List the family's apps in Admin, and devices you sign in will sign in to them too."
                : "Once an admin lists the family's apps here, devices you sign in will sign in to them too."}
            </p>
          </Empty>
        </section>
      )}
      <div className="app-grid">
        {apps.map((app) => <AppCard key={app.id} app={app} />)}
      </div>
    </div>
  );
}

function AppCard({ app }: { app: AppLink }) {
  const Icon = ICONS[app.id] ?? AppWindow;
  return (
    <a className="panel-card app-card" href={app.url} target="_blank" rel="noreferrer">
      <span className="app-icon"><Icon size={24} aria-hidden="true" /></span>
      <span className="app-text">
        <span className="app-name"><Breakable text={app.name} /></span>
        <span className="app-host">{host(app.url)}</span>
      </span>
      <ChevronRight size={20} className="app-chevron" aria-hidden="true" />
    </a>
  );
}
