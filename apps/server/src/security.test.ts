import { test } from "node:test";
import assert from "node:assert/strict";
import { Vault, sensitive, redactSensitive } from "./crypto.js";
import { config } from "./config.js";
const keys = {
  keyring: {
    v1: Buffer.alloc(32, 1).toString("base64"),
    v2: Buffer.alloc(32, 2).toString("base64"),
  },
  activeKey: "v1",
  lookupKey: Buffer.alloc(32, 3).toString("base64"),
};
test("authenticated encryption binds owner and message and supports retained key versions", () => {
  const v = new Vault(keys),
    sealed = v.seal({ body: "synthetic OTP 123456" }, "owner-a/message-a");
  assert.equal(
    v.open<{ body: string }>(sealed, "owner-a/message-a").body,
    "synthetic OTP 123456",
  );
  assert.throws(() => v.open(sealed, "owner-b/message-a"));
  const segments = sealed.split(".");
  const bytes = Buffer.from(segments[3]!, "base64url");
  bytes[0] = bytes[0]! ^ 1;
  segments[3] = bytes.toString("base64url");
  assert.throws(() => v.open(segments.join("."), "owner-a/message-a"));
  const rotated = new Vault({ ...keys, activeKey: "v2" });
  assert.equal(
    rotated.open<{ body: string }>(sealed, "owner-a/message-a").body,
    "synthetic OTP 123456",
  );
  assert.equal(rotated.seal({}, "ctx").split(".")[0], "v2");
});
test("sensitive scopes conservatively detect and redact codes and links", () => {
  assert.equal(sensitive("Reset your password: https://example.test/x"), true);
  assert.equal(sensitive("Your OTP 123456"), true);
  assert.equal(
    redactSensitive("Order 123456 at https://example.test/token"),
    "Order [number omitted] at [link omitted]",
  );
});
test("production fails closed for missing allowlist, emulator, insecure origin and wildcard CORS", () => {
  const env = {
    APP_ENV: "production",
    PUBLIC_BASE_URL: "https://example.test",
    MESSAGE_ENCRYPTION_KEYRING: JSON.stringify(keys.keyring),
    ACTIVE_MESSAGE_KEY_ID: "v1",
    LOOKUP_HMAC_KEY: keys.lookupKey,
    ALLOWED_GOOGLE_EMAILS: "owner@example.test",
    DATABASE_URL: "postgres://synthetic",
    FIREBASE_PROJECT_ID: "synthetic",
    FIREBASE_WEB_CONFIG: "{}",
    OAUTH_JWKS: "{}",
  };
  assert.doesNotThrow(() => config(env));
  assert.equal(config(env).registrationMode, "allowlist");
  assert.equal(
    config({ ...env, REGISTRATION_MODE: "google", ALLOWED_GOOGLE_EMAILS: "" })
      .registrationMode,
    "google",
  );
  assert.throws(() => config({ ...env, REGISTRATION_MODE: "all" }));
  assert.throws(() =>
    config({
      ...env,
      REGISTRATION_MODE: "google",
      ALLOWED_GOOGLE_EMAILS: "",
      OWNER_FIREBASE_UID: "synthetic-pin",
    }),
  );
  for (const patch of [
    { ALLOWED_GOOGLE_EMAILS: "" },
    { ALLOWED_GOOGLE_EMAILS: "*" },
    { PUBLIC_BASE_URL: "http://example.test" },
    { FIREBASE_AUTH_EMULATOR_HOST: "localhost:9099" },
    { DEBUG_AUTH: "1" },
    { CORS_ORIGIN: "*" },
    { TRUSTED_PROXY_CIDRS: "*" },
    { TRUSTED_PROXY_CIDRS: "0.0.0.0/0" },
    { TRUSTED_PROXY_CIDRS: "::/0" },
    { TRUSTED_PROXY_CIDRS: "10.0.0.1/99" },
    { CLOUDFLARE_PROXY_CIDRS: "192.0.2.0/24" },
  ])
    assert.throws(() => config({ ...env, ...patch }));
});
