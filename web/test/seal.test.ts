import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { open, seal, toBase64 } from "../src/lib/seal.ts";

// docs/PROTOCOL.md's test vector: every client's tests open it.
const VECTOR = {
  deviceId: "device-1",
  privateKey: "fPVfWTT+lmpkhYZJbYmgJjnb2Yk2z5N40jybPxGus/g=",
  publicKey: "BLjLkiLeA9LQO1HSvascpiMSjqDzL48RyznPjsAfHRkwvfHZWmiCDUIt1/tIu0HcMP5IJjCNMrVxG2v6lxnHJic=",
  sealed: {
    epk: "BCb8iyUOM3J9h1vLpRI9Fb3lmP+DrST3xeOkuCKM5hRqMMj7XwcscLCPmJQH1FOH5SM9QZNPDYbkJI6ZTey43pQ=",
    nonce: "od4tYckzNodlv4sX",
    ct: "wy4elJA1eGU8oFGPEuH4GkjWfSz0kD/j2Eq6oP97idy//cg4UoNeWX//frfkREIWSOpTXNp+tYXjJqaMcXGOB+hiqw==",
  },
  plain: '{"type":"clipboard","text":"hello from the aurora"}',
};

/** A device's keys as devices keep them, made the way a device would (not with the code under test). */
function newDevice(): { privateKey: string; publicKey: string } {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const jwk = privateKey.export({ format: "jwk" });
  const point = Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x!, "base64url"), Buffer.from(jwk.y!, "base64url")]);
  assert.equal(point.length, 65);
  void publicKey;
  return { privateKey: Buffer.from(jwk.d!, "base64url").toString("base64"), publicKey: point.toString("base64") };
}

test("opens the protocol's test vector", async () => {
  assert.equal(await open(VECTOR.sealed, VECTOR, VECTOR.deviceId), VECTOR.plain);
});

test("the test vector doesn't open for another device, or once changed", async () => {
  await assert.rejects(open(VECTOR.sealed, VECTOR, "device-2"));
  const ct = Buffer.from(VECTOR.sealed.ct, "base64");
  ct[0] ^= 1;
  await assert.rejects(open({ ...VECTOR.sealed, ct: ct.toString("base64") }, VECTOR, VECTOR.deviceId));
  await assert.rejects(open({ ...VECTOR.sealed, nonce: "AAAAAAAAAAAAAAAA" }, VECTOR, VECTOR.deviceId));
});

test("what's sealed for a device opens there", async () => {
  const device = newDevice();
  const plain = JSON.stringify({ type: "link", url: "https://example.com/a?b=c", title: "Señor Ünïcode ✨" });
  const sealed = await seal(plain, device.publicKey, "dev_abc");
  assert.equal(Buffer.from(sealed.epk, "base64").length, 65);
  assert.equal(Buffer.from(sealed.epk, "base64")[0], 4);
  assert.equal(Buffer.from(sealed.nonce, "base64").length, 12);
  assert.equal(Buffer.from(sealed.ct, "base64").length, Buffer.byteLength(plain) + 16);
  assert.equal(await open(sealed, device, "dev_abc"), plain);
});

test("sealing for the test vector's device opens with its key", async () => {
  const sealed = await seal(VECTOR.plain, VECTOR.publicKey, VECTOR.deviceId);
  assert.notEqual(sealed.ct, VECTOR.sealed.ct);
  assert.equal(await open(sealed, VECTOR, VECTOR.deviceId), VECTOR.plain);
});

test("it doesn't open under another device's id, or with another device's key", async () => {
  const device = newDevice();
  const other = newDevice();
  const sealed = await seal("hello", device.publicKey, "dev_abc");
  await assert.rejects(open(sealed, device, "dev_xyz"));
  await assert.rejects(open(sealed, other, "dev_abc"));
});

test("each sealing uses its own one-time key and nonce", async () => {
  const device = newDevice();
  const a = await seal("same", device.publicKey, "d");
  const b = await seal("same", device.publicKey, "d");
  assert.notEqual(a.epk, b.epk);
  assert.notEqual(a.nonce, b.nonce);
  assert.notEqual(a.ct, b.ct);
});

test("a public key that isn't a point on the curve is refused", async () => {
  const off = Buffer.from(VECTOR.publicKey, "base64");
  off[64] ^= 1;
  await assert.rejects(seal("x", off.toString("base64"), "d"), /Not a public key/);
  await assert.rejects(seal("x", toBase64(new Uint8Array(65)), "d"), /Not a public key/);
  await assert.rejects(seal("x", VECTOR.privateKey, "d"), /Not a public key/);
  await assert.rejects(seal("x", "not base64!", "d"), /Not a public key/);
  // The compressed form of a real key: devices exchange the uncompressed one only.
  const compressed = Buffer.concat([Buffer.from([2 + (off[64] & 1)]), Buffer.from(VECTOR.publicKey, "base64").subarray(1, 33)]);
  await assert.rejects(seal("x", compressed.toString("base64"), "d"), /Not a public key/);
});
