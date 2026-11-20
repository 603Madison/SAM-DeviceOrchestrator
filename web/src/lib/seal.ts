/*
 * Sealing something for one device, so only it can read it: the server in between can't. The
 * same as `Seal` in shared/ (Kotlin) and the Linux agent's, as docs/PROTOCOL.md says it:
 *
 *   1. a one-time P-256 key pair; `epk` is its public point (65 bytes, uncompressed);
 *   2. ECDH with the device's public key: the 32-byte X coordinate;
 *   3. HKDF-SHA256 (salt: the one-time point then the device's; info: "samo/v1/seal") to 32 bytes;
 *   4. AES-256-GCM with a random 12-byte nonce and the device's id as the associated data.
 *
 * WebCrypto only (browsers, and node for `npm test`). No imports, so node can run it as it is.
 * Browsers offer WebCrypto on https and localhost only: [canSeal] says whether it's there.
 */

export interface Sealed {
  /** The sender's one-time public key (P-256, uncompressed point, base64). */
  epk: string;
  /** 12 bytes, base64. */
  nonce: string;
  /** AES-256-GCM, tag at the end, base64. */
  ct: string;
}

const INFO = "samo/v1/seal";
const CURVE = { name: "ECDH", namedCurve: "P-256" } as const;

/** Whether this page can seal: browsers only give pages WebCrypto over https (or on localhost). */
export function canSeal(): boolean {
  return typeof globalThis.crypto !== "undefined" && !!globalThis.crypto.subtle;
}

type Bytes = Uint8Array<ArrayBuffer>;

function bytes(length: number): Bytes {
  return new Uint8Array(new ArrayBuffer(length));
}

export function fromBase64(text: string): Bytes {
  const binary = atob(text.trim());
  const out = bytes(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

export function toBase64(data: Uint8Array): string {
  let binary = "";
  for (const b of data) binary += String.fromCharCode(b);
  return btoa(binary);
}

function base64Url(data: Uint8Array): string {
  return toBase64(data).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** A public key as devices exchange it. Refuses anything that isn't a point on the curve (importKey checks). */
async function publicKey(text: string): Promise<{ key: CryptoKey; point: Bytes }> {
  let point: Bytes;
  try {
    point = fromBase64(text);
  } catch {
    throw new Error("Not a public key");
  }
  if (point.length !== 65 || point[0] !== 4) throw new Error("Not a public key");
  try {
    return { key: await crypto.subtle.importKey("raw", point, CURVE, false, []), point };
  } catch {
    throw new Error("Not a public key");
  }
}

/** The key both ends reach: ECDH, then HKDF-SHA256 over the two public points. */
async function derive(privateKey: CryptoKey, theirs: CryptoKey, epk: Bytes, recipient: Bytes, use: "encrypt" | "decrypt"): Promise<CryptoKey> {
  const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: theirs }, privateKey, 256);
  const salt = bytes(130);
  salt.set(epk, 0);
  salt.set(recipient, 65);
  const material = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt, info: new TextEncoder().encode(INFO) },
    material, { name: "AES-GCM", length: 256 }, false, [use],
  );
}

/** Seals [plain] for the device [deviceId], whose public key is [recipient]. */
export async function seal(plain: string, recipient: string, deviceId: string): Promise<Sealed> {
  const device = await publicKey(recipient);
  const oneTime = await crypto.subtle.generateKey(CURVE, true, ["deriveBits"]);
  const epk = new Uint8Array(await crypto.subtle.exportKey("raw", oneTime.publicKey));
  const key = await derive(oneTime.privateKey, device.key, epk, device.point, "encrypt");
  const nonce = crypto.getRandomValues(bytes(12));
  const ct = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: new TextEncoder().encode(deviceId), tagLength: 128 },
    key, new TextEncoder().encode(plain),
  );
  return { epk: toBase64(epk), nonce: toBase64(nonce), ct: toBase64(new Uint8Array(ct)) };
}

/**
 * Opens what was sealed for the device [deviceId], with its keys as devices keep them (the private
 * key is the 32-byte number, base64). Throws if it wasn't for this device, or was changed. The web
 * page never opens anything (it has no key of its own): this is here for the tests, which check
 * the page seals the way devices open.
 */
export async function open(sealed: Sealed, keys: { privateKey: string; publicKey: string }, deviceId: string): Promise<string> {
  const mine = await publicKey(keys.publicKey);
  const epk = await publicKey(sealed.epk);
  const privateKey = await crypto.subtle.importKey(
    "jwk",
    { kty: "EC", crv: "P-256", d: base64Url(fromBase64(keys.privateKey)), x: base64Url(mine.point.subarray(1, 33)), y: base64Url(mine.point.subarray(33, 65)) },
    CURVE, false, ["deriveBits"],
  );
  const key = await derive(privateKey, epk.key, epk.point, mine.point, "decrypt");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(sealed.nonce), additionalData: new TextEncoder().encode(deviceId), tagLength: 128 },
    key, fromBase64(sealed.ct),
  );
  return new TextDecoder("utf-8", { fatal: true }).decode(plain);
}
