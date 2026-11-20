import { useEffect } from "react";
import { BrowserRouter, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { safeReturn } from "./lib/paths";
import { session, useSession } from "./lib/session";
import { Sidebar, TabBar } from "./components/Navigation";
import { Spinner, Toasts } from "./components/ui";
import { Login } from "./pages/Login";
import { ChoosePassword } from "./pages/ChoosePassword";
import { ConnectApp } from "./pages/ConnectApp";
import { Devices } from "./pages/Devices";
import { Apps } from "./pages/Apps";
import { You } from "./pages/You";
import { Settings } from "./pages/Settings";
import { Admin } from "./pages/Admin";

export function App() {
  useEffect(() => { void session.start(); }, []);
  return (
    <BrowserRouter>
      <Root />
      <Toasts />
    </BrowserRouter>
  );
}

function Root() {
  const loading = useSession((s) => s.loading);
  const user = useSession((s) => s.user);
  const location = useLocation();
  const here = `${location.pathname}${location.search}`;

  if (loading) return <div className="splash"><Spinner size={32} /></div>;
  if (location.pathname === "/login") return user ? <Navigate to={safeReturn(new URLSearchParams(location.search).get("return"))} replace /> : <Login />;
  if (!user) return <Navigate to={`/login?return=${encodeURIComponent(here)}`} replace />;
  // An admin set the password: the person chooses their own before anything else opens.
  if (user.mustChangePassword) return <ChoosePassword />;
  if (location.pathname === "/connect-app") return <ConnectApp />;
  return <Shell />;
}

/** Around every page: the sidebar on a wide screen, the tabs along the bottom on a phone. */
function Shell() {
  const user = useSession((s) => s.user)!;
  const { pathname, search } = useLocation();
  // Each page starts at the top.
  useEffect(() => { document.querySelector(".content")?.scrollTo({ top: 0 }); }, [pathname, search]);
  return (
    <div className="shell">
      <Sidebar />
      <main className="content">
        <Routes>
          <Route path="/" element={<Devices />} />
          <Route path="/apps" element={<Apps />} />
          <Route path="/you" element={<You />} />
          <Route path="/settings" element={<Settings />} />
          {user.role === "ADMIN" && <Route path="/admin" element={<Admin />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <TabBar />
    </div>
  );
}
