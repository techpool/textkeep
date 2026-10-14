import { test } from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { randomUUID, randomBytes, createHash } from "node:crypto";
import { getAuth, type DecodedIdToken } from "firebase-admin/auth";
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
  async (t) => {
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
        registrationMode: "allowlist",
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
      for (const key of [
        "authorization_endpoint",
        "token_endpoint",
        "registration_endpoint",
        "revocation_endpoint",
        "jwks_uri",
      ])
        assert.ok(metadata[key].startsWith(origin + "/oauth/"), key);
      assert.ok(metadata.code_challenge_methods_supported.includes("S256"));
      const reg = await fetch(metadata.registration_endpoint, {
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
      const initialized = await fetch(origin + "/mcp", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + token,
          "Content-Type": "application/json",
          Accept: "application/json, text/event-stream",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 1,
          method: "initialize",
          params: {
            protocolVersion: "2025-11-25",
            capabilities: {},
            clientInfo: { name: "cache-policy-test", version: "1" },
          },
        }),
      });
      assert.equal(initialized.status, 200);
      assert.equal(initialized.headers.get("cache-control"), "no-store");
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
      // Only the Google identity boundary is stubbed in this local fixture.
      // Exercise the actual HTTP interaction, PKCE, adapter and token lifecycle.
      const googleUid = "http-google-fixture";
      t.mock.method(
        getAuth(),
        "verifySessionCookie",
        async () =>
          ({
            uid: googleUid,
            email: "owner@example.test",
            email_verified: true,
            auth_time: Math.floor(Date.now() / 1000),
            firebase: { sign_in_provider: "google.com" },
          }) as DecodedIdToken,
      );
      const jar = new Map<
        string,
        { name: string; value: string; path: string }
      >();
      jar.set("session:/", {
        name: "session",
        value: "synthetic-web-session",
        path: "/",
      });
      const browserFetch = async (path: string, options: RequestInit = {}) => {
        const target = new URL(path, origin);
        assert.equal(target.origin, origin);
        const cookie = [...jar.values()]
          .filter((c) => target.pathname.startsWith(c.path))
          .map((c) => c.name + "=" + c.value)
          .join("; ");
        const r = await fetch(target, {
          ...options,
          redirect: "manual",
          headers: { ...options.headers, Cookie: cookie },
        });
        for (const value of r.headers.getSetCookie()) {
          const [pair, ...attributes] = value.split(";");
          const at = pair!.indexOf("=");
          const name = pair!.slice(0, at),
            content = pair!.slice(at + 1);
          const path =
            attributes
              .find((a) => a.trim().toLowerCase().startsWith("path="))
              ?.trim()
              .slice(5) || "/";
          const key = name + ":" + path;
          if (!content || attributes.some((a) => /max-age=0/i.test(a)))
            jar.delete(key);
          else jar.set(key, { name, value: content, path });
        }
        return r;
      };
      const fixtureCsrf = (await (
        await browserFetch("/api/v1/auth/csrf")
      ).json()) as {
        token: string;
      };
      const repeatedCsrf = await browserFetch("/api/v1/auth/csrf");
      assert.equal(
        ((await repeatedCsrf.json()) as any).token,
        fixtureCsrf.token,
        "Another tab must not invalidate the browser's existing confirmation token",
      );
      const expiredInteraction = await browserFetch("/app/access/interaction");
      assert.equal(expiredInteraction.status, 400);
      assert.match(
        ((await expiredInteraction.json()) as any).error,
        /expired.*Restart/,
      );
      const verifier = randomBytes(48).toString("base64url");
      const state = randomUUID();
      const grantParams = new URLSearchParams({
        ...Object.fromEntries(params),
        resource: origin + "/mcp",
        scope: "openid offline_access messages:read messages:sensitive",
        state,
        prompt: "consent",
        code_challenge: createHash("sha256")
          .update(verifier)
          .digest("base64url"),
        code_challenge_method: "S256",
      });
      let response = await browserFetch(
        metadata.authorization_endpoint + "?" + grantParams,
      );
      const interactionTrace: string[] = [];
      let callback: URL | undefined;
      for (let step = 0; step < 8; step++) {
        const destination = response.headers.get("location");
        assert.ok(destination, "OAuth response must redirect");
        if (destination.startsWith("http://127.0.0.1:51230/callback")) {
          callback = new URL(destination);
          break;
        }
        const next = new URL(destination, origin);
        interactionTrace.push(next.pathname);
        if (next.pathname === "/app/access") {
          const interaction = await browserFetch("/app/access/interaction");
          assert.equal(interaction.status, 200);
          const observedPrompt = ((await interaction.json()) as any).prompt;
          interactionTrace.push(observedPrompt);
          assert.ok(["login", "consent"].includes(observedPrompt));
          response = await browserFetch("/app/access/consent", {
            method: "POST",
            headers: {
              Origin: origin,
              "Content-Type": "application/json",
              "X-CSRF-Token": fixtureCsrf.token,
            },
            body: JSON.stringify({ approve: true, sensitive: false }),
          });
          assert.equal(response.status, 200);
          assert.equal(response.headers.get("location"), null);
          const continuation = (
            (await response.json()) as { redirectTo: string }
          ).redirectTo;
          assert.equal(new URL(continuation, origin).origin, origin);
          assert.ok(
            new URL(continuation, origin).pathname.startsWith("/oauth/"),
          );
          response = await browserFetch(continuation);
        } else response = await browserFetch(next.href);
      }
      assert.ok(callback, JSON.stringify(interactionTrace));
      assert.equal(callback?.searchParams.get("state"), state);
      assert.ok(callback?.searchParams.get("code"));
      const exchange = async (fields: Record<string, string>) => {
        const r = await fetch(metadata.token_endpoint, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            client_id: clientMeta.client_id,
            resource: origin + "/mcp",
            ...fields,
          }),
        });
        return { status: r.status, ...((await r.json()) as any) };
      };
      const issued = await exchange({
        grant_type: "authorization_code",
        code: callback!.searchParams.get("code")!,
        redirect_uri: "http://127.0.0.1:51230/callback",
        code_verifier: verifier,
      });
      assert.equal(issued.status, 200);
      assert.ok(issued.scope.split(" ").includes("messages:read"));
      assert.ok(
        !issued.scope.split(" ").includes("messages:sensitive"),
        "Declining sensitive access must reduce scope without repeating consent",
      );
      assert.ok(issued.refresh_token);
      const authorized = new Client({ name: "oauth-http-test", version: "1" });
      await authorized.connect(
        new StreamableHTTPClientTransport(new URL(origin + "/mcp"), {
          requestInit: {
            headers: { Authorization: "Bearer " + issued.access_token },
          },
        }),
      );
      assert.equal((await authorized.listTools()).tools.length, 5);
      assert.match(
        JSON.stringify(
          await authorized.callTool({ name: "get_sync_status", arguments: {} }),
        ),
        /freshness/,
      );
      await authorized.close();
      const rotated = await exchange({
        grant_type: "refresh_token",
        refresh_token: issued.refresh_token,
      });
      assert.equal(rotated.status, 200);
      assert.notEqual(rotated.refresh_token, issued.refresh_token);
      assert.equal(
        (
          await exchange({
            grant_type: "refresh_token",
            refresh_token: issued.refresh_token,
          })
        ).status,
        400,
      );
      assert.equal(
        (
          await exchange({
            grant_type: "refresh_token",
            refresh_token: rotated.refresh_token,
          })
        ).status,
        400,
      );
      const revoked = await fetch(origin + "/mcp", {
        method: "POST",
        headers: {
          Authorization: "Bearer " + rotated.access_token,
          "Content-Type": "application/json",
        },
        body: "{}",
      });
      assert.equal(revoked.status, 401);
      await db.query("DELETE FROM textkeep.users WHERE uid=$1", [googleUid]);
    } finally {
      await app.close();
    }
  },
);
