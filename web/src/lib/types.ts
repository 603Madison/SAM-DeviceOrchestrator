/* The API's shapes: copies of the server's models (shared/…/com/orchestrator/api). Keep them in step. */

export type Role = "ADMIN" | "USER";

export interface User {
  id: string;
  username: string;
  displayName: string;
  role: Role;
  breakGlass: boolean;
  disabled: boolean;
  oidc: boolean;
  hasPassword: boolean;
  createdAt: number;
  lastLoginAt?: number;
  mustChangePassword: boolean;
  passwordSetByAdminAt?: number;
}

export type SessionKind = "WEB" | "APP";

export interface SessionInfo {
  id: string;
  kind: SessionKind;
  name: string;
  createdAt: number;
  lastSeenAt: number;
  expiresAt: number;
  current: boolean;
}

export interface AuthConfig {
  serverName: string;
  version: string;
  localLogin: boolean;
  oidc?: { label: string };
}

export interface Me { user: User; session: SessionInfo }

// ---- Devices ----

export type DeviceKind = "LINUX" | "ANDROID" | "OTHER";

export interface Battery { level: number; charging: boolean }

/** What a device can do with what it's sent. */
export type Capability = "notifications" | "clipboard" | "links" | "ring" | "activity" | "media";

/** One of a person's signed-in devices. */
export interface Device {
  id: string;
  name: string;
  kind: DeviceKind;
  /** "Pixel 9", "ThinkPad X1": whatever the device calls itself. */
  model: string;
  /** P-256, the 65-byte uncompressed point, base64: what's sent to it is sealed for this. */
  publicKey: string;
  capabilities: string[];
  online: boolean;
  lastSeenAt: number;
  createdAt: number;
  battery?: Battery;
  /** The device asking: never true for the web page. */
  current: boolean;
}

// ---- The family's apps ----

/** One of the family's servers, as the admin listed it. */
export interface AppLink {
  /** "music", "health", "video", "maps", "email". */
  id: string;
  name: string;
  url: string;
}

// ---- Messages between devices ----

/** Something sealed for one device: the server passes it on and can't read it. */
export type { Sealed } from "./seal";
import type { Sealed } from "./seal";

export interface OutgoingMessage {
  /** Device id to what's sealed for it. */
  to: Record<string, Sealed>;
  /** How long the server keeps it for a device that's away, in seconds. */
  ttlSeconds: number;
  /** A newer one with the same key replaces an undelivered older one. */
  collapseKey?: string;
}

export interface SentMessage { delivered: string[]; queued: string[] }

/** What's inside a sealed message, of the kinds the web page sends. Devices know more (docs/PROTOCOL.md). */
export type Payload =
  | { type: "clipboard"; text: string }
  | { type: "link"; url: string; title: string }
  | { type: "ring"; on: boolean };

// ---- Admin ----

export interface OidcSettings {
  enabled: boolean;
  issuer: string;
  clientId: string;
  scopes: string;
  buttonLabel: string;
  usernameClaim: string;
  groupsClaim: string;
  userGroup: string;
  adminGroup: string;
  autoCreateUsers: boolean;
}

export interface ServerSettings {
  serverName: string;
  publicUrl: string;
  localLogin: boolean;
  oidc: OidcSettings;
  /** The family's servers: where signed-in devices find each app. */
  apps: AppLink[];
  /** How long something sent to a device that's away is kept for it, in hours. */
  keepHours: number;
}

export interface AdminSettings {
  settings: ServerSettings;
  oidcClientSecretSet: boolean;
  redirectUri: string;
  /** Settings fixed by environment variables (SAMO_APPS, SAMO_PUBLIC_URL, …), which win at every start. */
  fromEnvironment: string[];
  version: string;
}
