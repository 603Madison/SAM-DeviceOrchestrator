import { test } from "node:test";
import assert from "node:assert/strict";
import { APP_REDIRECT, appRedirect, withCode } from "../src/lib/redirect.ts";

test("the Android app's address, and nothing named", () => {
  assert.equal(appRedirect("samorchestrator://signed-in"), APP_REDIRECT);
  assert.equal(appRedirect(null), APP_REDIRECT);
  assert.equal(appRedirect(undefined), APP_REDIRECT);
  assert.equal(appRedirect(""), APP_REDIRECT);
});

test("this computer, where the Linux agent listens", () => {
  assert.equal(appRedirect("http://127.0.0.1:43123/signed-in"), "http://127.0.0.1:43123/signed-in");
  assert.equal(appRedirect("http://localhost:43123/signed-in"), "http://localhost:43123/signed-in");
  assert.equal(appRedirect("http://127.0.0.1:43123"), "http://127.0.0.1:43123/");
  assert.equal(appRedirect("http://127.0.0.1:8/cb?state=abc"), "http://127.0.0.1:8/cb?state=abc");
  assert.equal(appRedirect("http://localhost:5000/cb#frag"), "http://localhost:5000/cb");
});

test("never another site", () => {
  const hostile = [
    "http://127.0.0.1.evil.com",
    "http://127.0.0.1.evil.com:8080/",
    "http://127.0.0.1@evil.com",
    "http://127.0.0.1:80@evil.com/",
    "http://user:pw@127.0.0.1:8080/",
    "http://user@localhost:8080/",
    "https://127.0.0.1",
    "https://127.0.0.1:8443/",
    "https://localhost:8443/",
    "javascript:alert(1)",
    "javascript://127.0.0.1:80/%0aalert(1)",
    "data:text/html,<script>alert(1)</script>",
    "http://evil.com",
    "https://evil.com/?http://127.0.0.1:1/",
    "//evil.com",
    "/\\evil.com",
    "http://localhost.evil.com:80/",
    "http://localhost.:8080/",
    "http://evil.com#http://127.0.0.1:1/",
    "http://127.0.0.1:8080\\@evil.com/",
    "http://127.0.0.1:8080 @evil.com/",
    "http://127.0.0.1:80\t80/",
    " http://127.0.0.1:8080/",
    "http://127.0.0.1:8080/\n",
    "HTTP://127.0.0.1:8080/",
    "http:127.0.0.1:8080",
    "http:/127.0.0.1:8080/",
    "http://127.0.0.1/",
    "http://localhost/",
    "http://127.0.0.1:/",
    "http://127.0.0.1:99999999/",
    "http://127.0.0.2:8080/",
    "http://127.1:8080/",
    "http://2130706433:8080/",
    "http://0x7f.0.0.1:8080/",
    "http://[::1]:8080/",
    "http://0.0.0.0:8080/",
    "http://192.168.1.10:8080/",
    "http://xn--lcalhost-9ya:8080/",
    "http://lоcalhost:8080/", // a Cyrillic о
    "samorchestrator://signed-in/",
    "samorchestrator://signed-in?next=https://evil.com",
    "samorchestrator://evil",
    "SAMORCHESTRATOR://signed-in",
    "samvideo://signed-in",
    "intent://signed-in#Intent;scheme=samorchestrator;end",
    "file:///etc/passwd",
    "about:blank",
  ];
  for (const address of hostile) assert.equal(appRedirect(address), null, address);
});

test("the code goes after ?, or & when there's a query already", () => {
  assert.equal(withCode(APP_REDIRECT, "abc"), "samorchestrator://signed-in?code=abc");
  assert.equal(withCode("http://127.0.0.1:43123/signed-in", "a b&c"), "http://127.0.0.1:43123/signed-in?code=a%20b%26c");
  assert.equal(withCode("http://127.0.0.1:8/cb?state=abc", "xyz"), "http://127.0.0.1:8/cb?state=abc&code=xyz");
  assert.equal(new URL(withCode(appRedirect("http://localhost:9/cb?x=1#frag")!, "c0de")).searchParams.get("code"), "c0de");
});
