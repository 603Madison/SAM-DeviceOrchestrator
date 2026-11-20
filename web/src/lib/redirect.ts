/*
 * Where a device's sign-in may send the one-time code (npm test covers it). No imports, so node
 * can run it as it is.
 *
 * Any page or app could open /connect-app with any `redirect`, so only two kinds are let through
 * (docs/PROTOCOL.md): the Android app's own address, and this computer itself, where the Linux
 * agent listens for a moment. Never another site.
 */

/** The Android app's address, and where the code goes when the link names nowhere. */
export const APP_REDIRECT = "samorchestrator://signed-in";

/**
 * The address to send the code to, tidied, or null when [redirect] isn't allowed: exactly
 * `samorchestrator://signed-in`, or `http://127.0.0.1:<port>/…` / `http://localhost:<port>/…`
 * (plain http, a port, no name or password in it). Missing: the Android app's.
 */
export function appRedirect(redirect: string | null | undefined): string | null {
  if (redirect == null || redirect === "") return APP_REDIRECT;
  if (redirect === APP_REDIRECT) return APP_REDIRECT;
  // Nothing a browser would tidy into something else: spaces, controls, backslashes.
  if (/[\u0000- \u007f\\]/.test(redirect)) return null;
  if (!/^http:\/\/(127\.0\.0\.1|localhost):[0-9]{1,5}(\/|\?|$)/.test(redirect)) return null;
  let url: URL;
  try {
    url = new URL(redirect);
  } catch {
    return null;
  }
  if (url.protocol !== "http:") return null;
  if (url.hostname !== "127.0.0.1" && url.hostname !== "localhost") return null;
  if (url.username !== "" || url.password !== "") return null;
  if (url.port === "") return null;
  url.hash = "";
  return url.href;
}

/** [redirect] (one [appRedirect] allowed) with the one-time code added: after `?`, or `&` when it has a query already. */
export function withCode(redirect: string, code: string): string {
  return `${redirect}${redirect.includes("?") ? "&" : "?"}code=${encodeURIComponent(code)}`;
}
