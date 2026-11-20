import { pairingCode } from "../src/lib/pairing.ts";
import { test } from "node:test";
import assert from "node:assert/strict";
import { ago, percent, webAddress } from "../src/lib/format.ts";
import { safeReturn } from "../src/lib/paths.ts";
import { backFor, tabOf } from "../src/lib/nav.ts";

test("how long ago", () => {
  const now = Date.UTC(2026, 0, 10, 12);
  assert.equal(ago(now - 20_000, now), "just now");
  assert.equal(ago(now - 5 * 60_000, now), "5 minutes ago");
  assert.equal(ago(now - 3600_000, now), "1 hour ago");
  assert.equal(ago(now - 3 * 86400_000, now), "3 days ago");
  assert.equal(ago(now - 800 * 86400_000, now), "2 years ago");
  assert.equal(ago(now + 60_000, now), "just now");
  assert.equal(ago(undefined, now), "");
});

test("battery levels", () => {
  assert.equal(percent(81), "81%");
  assert.equal(percent(80.6), "81%");
  assert.equal(percent(140), "100%");
  assert.equal(percent(-3), "0%");
});

test("links to send are web addresses", () => {
  assert.equal(webAddress("example.com/page"), "https://example.com/page");
  assert.equal(webAddress("  https://example.com/a?b=c "), "https://example.com/a?b=c");
  assert.equal(webAddress("http://localhost:8080/x"), "http://localhost:8080/x");
  assert.equal(webAddress(""), null);
  assert.equal(webAddress("two words"), null);
  assert.equal(webAddress("javascript:alert(1)"), null);
  assert.equal(webAddress("file:///etc/passwd"), null);
  assert.equal(webAddress("hello"), null);
});

test("after signing in, only pages on this server", () => {
  assert.equal(safeReturn("/connect-app?challenge=abc&redirect=http%3A%2F%2F127.0.0.1%3A1%2F"), "/connect-app?challenge=abc&redirect=http%3A%2F%2F127.0.0.1%3A1%2F");
  assert.equal(safeReturn("/apps"), "/apps");
  assert.equal(safeReturn("//evil.com"), "/");
  assert.equal(safeReturn("/\\evil.com"), "/");
  assert.equal(safeReturn("https://evil.com"), "/");
  assert.equal(safeReturn(null), "/");
});

test("tabs and back arrows", () => {
  assert.equal(tabOf("/"), "devices");
  assert.equal(tabOf("/apps"), "apps");
  assert.equal(tabOf("/settings"), "you");
  assert.equal(tabOf("/admin"), "you");
  assert.equal(backFor("/"), null);
  assert.equal(backFor("/you"), null);
  assert.deepEqual(backFor("/admin"), { to: "/you", label: "You" });
  assert.deepEqual(backFor("/nowhere"), { to: "/", label: "Devices" });
});

test("pairing codes are six digits from the challenge, the same as the devices work out", () => {
  const code = pairingCode("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  assert.match(code, /^\d{3} \d{3}$/);
  assert.equal(code, "249 228");
  assert.notEqual(code, pairingCode("F9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cN"));
});
