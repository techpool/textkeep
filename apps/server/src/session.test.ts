import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import pg from "pg";
import type { DecodedIdToken } from "firebase-admin/auth";
import type { FastifyRequest } from "fastify";
import { Auth } from "./auth.js";
import { Vault, secretHash } from "./crypto.js";
import { migrate } from "./database.js";
import type { Config } from "./config.js";

test(
  "browser logout revokes its cookie while preserving other sessions and phone credentials",
  { skip: !process.env.TEST_DATABASE_URL },
  async (t) => {
    const url = process.env.TEST_DATABASE_URL!;
    assert.match(url, /localhost|127\.0\.0\.1/);
    const db = new pg.Pool({ connectionString: url });
    await migrate(db);
    const uid = "session-test-" + randomUUID();
    const email = uid + "@example.test";
    const c: Config = {
      production: false,
      origin: "http://127.0.0.1:55439",
      databaseUrl: url,
      firebaseWeb: {},
      oauthJwks: {},
      port: 55439,
      firebaseProject: "synthetic-auth",
      registrationMode: "allowlist",
      allowedEmails: [email],
      keyring: { v1: Buffer.alloc(32, 1).toString("base64") },
      activeKey: "v1",
      lookupKey: Buffer.alloc(32, 2).toString("base64"),
    };
    const auth = new Auth(c, db, new Vault(c));
    const decoded = {
      uid,
      email,
      email_verified: true,
      auth_time: Math.floor(Date.now() / 1000),
      firebase: { sign_in_provider: "google.com" },
    } as DecodedIdToken;
    const cookies = [randomUUID(), randomUUID()];
    t.mock.method(auth.firebase, "verifySessionCookie", async () => decoded);
    t.mock.method(auth.firebase, "verifyIdToken", async () => decoded);
    const globalRevoke = t.mock.method(
      auth.firebase,
      "revokeRefreshTokens",
      async () => {
        throw Error("Must not revoke phone credentials");
      },
    );
    const request = (session: string) => ({
      headers: {},
      cookies: { session },
    });
    try {
      assert.equal((await auth.identity(request(cookies[0]!))).uid, uid);
      await auth.revokeSession(cookies[0]);
      await assert.rejects(auth.identity(request(cookies[0]!)), {
        statusCode: 401,
      });
      assert.equal((await auth.identity(request(cookies[1]!))).uid, uid);
      assert.equal(
        (
          await auth.identity({
            headers: { authorization: "Bearer synthetic-phone-token" },
            cookies: {},
          })
        ).kind,
        "firebase",
      );
      assert.equal(globalRevoke.mock.callCount(), 0);
      const stored = await db.query(
        "SELECT hash FROM textkeep.revoked_web_sessions WHERE hash=$1",
        [secretHash(cookies[0]!)],
      );
      assert.equal(stored.rows[0].hash, secretHash(cookies[0]!));
      assert.notEqual(stored.rows[0].hash, cookies[0]);
      // Public Google registration must not turn the primary owner's UID pin
      // into access to anyone else's archive, or reject independent accounts.
      c.registrationMode = "google";
      c.ownerUid = uid;
      c.ownerEmail = email;
      const guest = {
        ...decoded,
        uid: uid + "-guest",
        email: "guest-" + email,
      };
      try {
        assert.equal((await auth.bind(guest)).uid, guest.uid);
        assert.equal((await auth.bind(decoded)).uid, uid);
        for (const rejected of [
          { ...guest, email_verified: false },
          { ...guest, firebase: { sign_in_provider: "password" } },
          { ...guest, email },
          { ...decoded, email: guest.email },
        ])
          await assert.rejects(auth.bind(rejected as DecodedIdToken), {
            statusCode: 403,
          });
        await db.query("UPDATE textkeep.users SET disabled=true WHERE uid=$1", [
          guest.uid,
        ]);
        await assert.rejects(auth.bind(guest), { statusCode: 403 });
        c.registrationMode = "allowlist";
        await assert.rejects(auth.bind({ ...guest, uid: uid + "-uninvited" }), {
          statusCode: 403,
        });
      } finally {
        await db.query("DELETE FROM textkeep.users WHERE uid=$1", [guest.uid]);
      }
    } finally {
      await db.query(
        "DELETE FROM textkeep.revoked_web_sessions WHERE hash=$1",
        [secretHash(cookies[0]!)],
      );
      await db.query("DELETE FROM textkeep.users WHERE uid=$1", [uid]);
      await db.end();
    }
  },
);
