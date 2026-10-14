import { test } from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { randomUUID } from "node:crypto";
import { generateKeyPair, exportJWK } from "jose";
import { buildApp } from "./app.js";
import { migrate } from "./database.js";
import { secretHash } from "./crypto.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
const url = process.env.TEST_DATABASE_URL;
test(
  "HTTP health, auth denial, CSRF origin, OAuth registration/PKCE and actual Streamable HTTP client",
  { skip: !url },
  async () => {
    assert.match(url!, /localhost|127\.0\.0\.1/);
    const db = new pg.Pool({ connectionString: url });
    await migrate(db);
    const { privateKey } = await generateKeyPair("RS256", {
      extractable: true,
    });
    const jwk = await exportJWK(privateKey);
    jwk.kid = "synthetic";
    jwk.alg = "RS256";
    const origin = "http://127.0.0.1:55439";
    const app = await buildApp(
      {
        production: false,
        origin,
        databaseUrl: url!,
        firebaseProject: "synthetic-auth",
        allowedEmails: ["owner@example.test"],
        keyring: { v1: Buffer.alloc(32, 1).toString("base64") },
        activeKey: "v1",
        lookupKey: Buffer.alloc(32, 2).toString("base64"),
        firebaseWeb: { projectId: "synthetic-auth" },
        oauthJwks: { keys: [jwk] },
        port: 55439,
      },
      db,
    );
    await app.listen({ host: "127.0.0.1", port: 55439 });
    try {
      for (const path of [
        "/health/live",
        "/health/ready",
        "/.well-known/oauth-protected-resource/mcp",
        "/oauth/.well-known/oauth-authorization-server",
      ]) {
        const r = await fetch(origin + path);
        assert.equal(r.status, 200, path);
      }
      for (const path of [
        "/api/v1/me",
        "/api/v1/messages",
        "/api/v1/devices",
        "/api/v1/threads",
        "/api/v1/sync/history",
      ]) {
        const r = await fetch(origin + path);
        assert.equal(r.status, 401, path);
        assert.equal(r.headers.get("cache-control"), "no-store");
      }
      const denied = await fetch(origin + "/mcp", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      assert.equal(denied.status, 401);
      assert.match(
        denied.headers.get("www-authenticate") || "",
        /resource_metadata/,
      );
      const csrf = await fetch(origin + "/api/v1/auth/session", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Origin: "https://evil.example",
        },
        body: JSON.stringify({ idToken: "synthetic-invalid-token-value" }),
      });
      assert.equal(csrf.status, 403);
      const metadata = (await (
        await fetch(origin + "/oauth/.well-known/oauth-authorization-server")
      ).json()) as any;
      assert.equal(metadata.issuer, origin + "/oauth");
      assert.ok(metadata.code_challenge_methods_supported.includes("S256"));
      const reg = await fetch(origin + "/oauth/reg", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          client_name: "Synthetic test client",
          redirect_uris: ["http://127.0.0.1:51230/callback"],
          application_type: "native",
          grant_types: ["authorization_code", "refresh_token"],
          response_types: ["code"],
          token_endpoint_auth_method: "none",
        }),
      });
      assert.equal(reg.status, 201, await reg.clone().text());
      const clientMeta = (await reg.json()) as any;
      const params = new URLSearchParams({
        client_id: clientMeta.client_id,
        redirect_uri: "http://127.0.0.1:51230/callback",
        response_type: "code",
        scope: "messages:read",
        resource: origin + "/mcp",
        state: "synthetic",
      });
      const pkce = await fetch(origin + "/oauth/auth?" + params, {
        redirect: "manual",
      });
      assert.equal(pkce.status, 303);
      assert.match(pkce.headers.get("location") || "", /invalid_request/);
      params.set("code_challenge", "a".repeat(43));
      params.set("code_challenge_method", "S256");
      params.set("resource", "https://evil.example/mcp");
      const audience = await fetch(origin + "/oauth/auth?" + params, {
        redirect: "manual",
      });
      assert.ok(
        audience.status === 400 ||
          /invalid_target/.test(audience.headers.get("location") || ""),
      );
      const owner = "http-test-" + randomUUID(),
        token = "tk_" + randomUUID();
      await db.query(
        "INSERT INTO textkeep.users(uid,email_digest,consent_version) VALUES($1,$2,$3)",
        [owner, secretHash(owner), "test"],
      );
      await db.query(
        "INSERT INTO textkeep.personal_access_tokens(id,owner,name,hash,expires_at) VALUES($1,$2,$3,$4,now()+interval '1 hour')",
        [randomUUID(), owner, "Synthetic token", secretHash(token)],
      );
      const client = new Client({
        name: "http-synthetic-client",
        version: "1.0.0",
      });
      await client.connect(
        new StreamableHTTPClientTransport(new URL(origin + "/mcp"), {
          requestInit: { headers: { Authorization: "Bearer " + token } },
        }),
      );
      const tools = await client.listTools();
      assert.equal(tools.tools.length, 5);
      const status = await client.callTool({
        name: "get_sync_status",
        arguments: {},
      });
      assert.match(JSON.stringify(status), /freshness/);
      await client.close();
    } finally {
      await app.close();
    }
  },
);
