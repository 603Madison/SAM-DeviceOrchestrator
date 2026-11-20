import { pairingCode } from "../lib/pairing";
import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { MonitorSmartphone } from "lucide-react";
import { api } from "../lib/api";
import { useSession } from "../lib/session";
import { Button } from "../components/ui";
import { appRedirect, withCode } from "../lib/redirect";

/**
 * A device's sign-in: its agent (the Android app, or SuperAwesomeMagicalLinux) opens this page in
 * a browser; once the person is signed in here and agrees, the agent gets a one-time code for its
 * own sign-in, at the address it named (lib/redirect.ts says which addresses are allowed).
 */
export function ConnectApp() {
  const [params] = useSearchParams();
  const user = useSession((s) => s.user);
  const config = useSession((s) => s.config);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const challenge = params.get("challenge") ?? "";
  // Said as the link says it: anything could have opened this page.
  const device = (params.get("name") ?? "").replace(/[^\p{L}\p{N} ._()'-]/gu, "").trim().slice(0, 60) || "a device";
  const redirect = appRedirect(params.get("redirect"));
  const valid = /^[A-Za-z0-9_-]{43}$/.test(challenge) && redirect !== null;

  const approve = async () => {
    if (!redirect) return;
    setBusy(true);
    setError(null);
    try {
      const { code } = await api.appCode(challenge);
      setDone(true);
      window.location.assign(withCode(redirect, code));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="connect-icon"><MonitorSmartphone size={36} /></div>
        {!valid ? (
          <>
            <h1>That link isn't right</h1>
            <p className="login-sub">
              {redirect === null
                ? "It would send your sign-in somewhere that isn't your device, so nothing was sent. Start signing in from your device again."
                : "Start signing in from your device again."}
            </p>
          </>
        ) : done ? (
          <>
            <h1>You're signed in</h1>
            <p className="login-sub">Head back to your device. You can close this tab.</p>
          </>
        ) : (
          <>
            <h1>Sign in on this device?</h1>
            <p className="login-sub">
              <strong>{device}</strong> wants to sign in to {config?.serverName ?? "this server"} as{" "}
              <strong>{user?.displayName}</strong> ({user?.username}). It will be able to sign your apps in on this device, and share
              notifications, the clipboard and links with your other devices.
            </p>
            <div className="pairing-code" aria-label={`Pairing code ${pairingCode(challenge)}`}>{pairingCode(challenge)}</div>
            <p className="login-sub">Your device shows this code too. Only carry on if they match.</p>
            {error && <div className="notice notice-error" role="alert">{error}</div>}
            <Button variant="primary" className="wide" busy={busy} onClick={approve}>Sign in on this device</Button>
            <Button variant="ghost" className="wide" onClick={() => window.close()}>Cancel</Button>
            <p className="login-version">Only continue if you started this from your device yourself.</p>
          </>
        )}
      </div>
    </div>
  );
}
