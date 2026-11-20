import type {
  AdminSettings, AppLink, AuthConfig, Device, Me, OidcSettings, OutgoingMessage, SentMessage, ServerSettings, SessionInfo, User,
} from "./types";

/** An error from the server, with a message meant for people. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Told when the server says the sign-in is gone, so the app can show the sign-in page. */
let onSignedOut: (() => void) | null = null;
export function setSignedOutHandler(handler: () => void) {
  onSignedOut = handler;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  // Every change carries this header, which other sites' pages can't send.
  if (method !== "GET") headers["X-SAMO"] = "1";
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "The server can't be reached. Check your connection.");
  }
  if (response.status === 401 && !path.startsWith("/api/auth/")) onSignedOut?.();
  const text = await response.text();
  const data = text ? (() => { try { return JSON.parse(text); } catch { return null; } })() : null;
  if (!response.ok) throw new ApiError(response.status, (data && typeof data.error === "string" && data.error) || `The server answered ${response.status}`);
  return data as T;
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {});
const put = <T>(path: string, body: unknown) => request<T>("PUT", path, body);
const patch = <T>(path: string, body: unknown) => request<T>("PATCH", path, body);
const del = <T>(path: string) => request<T>("DELETE", path);
const q = encodeURIComponent;

export const api = {
  // Signing in
  authConfig: () => get<AuthConfig>("/api/auth/config"),
  me: () => get<Me>("/api/me"),
  login: (username: string, password: string) => post<{ user: User }>("/api/auth/login", { username, password }),
  logout: () => post<{ ok: boolean }>("/api/auth/logout"),
  appCode: (challenge: string) => post<{ code: string }>("/api/auth/app/code", { challenge }),
  updateMe: (displayName: string) => patch<User>("/api/me", { displayName }),
  changePassword: (current: string, next: string) => post<{ ok: boolean }>("/api/me/password", { current, new: next }),
  sessions: () => get<SessionInfo[]>("/api/me/sessions"),
  revokeSession: (id: string) => del<{ ok: boolean }>(`/api/me/sessions/${q(id)}`),

  // Devices
  devices: () => get<Device[]>("/api/devices"),
  renameDevice: (id: string, name: string) => patch<Device>(`/api/devices/${q(id)}`, { name }),
  /** Removes a device, which signs it out. */
  removeDevice: (id: string) => del<{ ok: boolean }>(`/api/devices/${q(id)}`),
  /** Sends what was sealed for each device (lib/seal.ts): the server only passes it on. */
  send: (message: OutgoingMessage) => post<SentMessage>("/api/messages", message),

  // The family's apps
  apps: () => get<AppLink[]>("/api/apps"),

  // Admin
  users: () => get<User[]>("/api/admin/users"),
  createUser: (username: string, displayName: string, password: string, role: string) =>
    post<User>("/api/admin/users", { username, displayName, password, role }),
  updateUser: (id: string, change: { displayName?: string; role?: string; disabled?: boolean }) => patch<User>(`/api/admin/users/${q(id)}`, change),
  deleteUser: (id: string) => del<{ ok: boolean }>(`/api/admin/users/${q(id)}`),
  resetPassword: (id: string, password: string) => post<{ ok: boolean }>(`/api/admin/users/${q(id)}/password`, { password }),
  signOutUser: (id: string) => del<{ ok: boolean }>(`/api/admin/users/${q(id)}/sessions`),
  adminSettings: () => get<AdminSettings>("/api/admin/settings"),
  saveSettings: (settings: ServerSettings, oidcClientSecret?: string) =>
    put<AdminSettings>("/api/admin/settings", { settings, ...(oidcClientSecret !== undefined ? { oidcClientSecret } : {}) }),
  testOidc: (oidc: OidcSettings) => post<{ ok: boolean; message: string }>("/api/admin/oidc/test", oidc),
};
