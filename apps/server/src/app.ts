import Fastify from "fastify";
import cookie from "@fastify/cookie";
import helmet from "@fastify/helmet";
import rateLimit from "@fastify/rate-limit";
import staticPlugin from "@fastify/static";
import cors from "@fastify/cors";
import swagger from "@fastify/swagger";
import swaggerUi from "@fastify/swagger-ui";
import {
  jsonSchemaTransform,
  serializerCompiler,
  validatorCompiler,
  type ZodTypeProvider,
} from "fastify-type-provider-zod";
import { z } from "zod";
import { randomBytes, randomUUID } from "node:crypto";
import { resolve } from "node:path";
import { createReadStream, existsSync } from "node:fs";
import type pg from "pg";
import type { Config } from "./config.js";
import { Vault, secretHash } from "./crypto.js";
import { Auth, HttpError, type Identity } from "./auth.js";
import { Service } from "./service.js";
import { oauth } from "./oauth.js";
import { mcpServer, StreamableHTTPServerTransport } from "./mcp.js";
import {
  searchQuery,
  syncBatch,
} from "../../../packages/contracts/dist/index.js";
export async function buildApp(c: Config, db: pg.Pool) {
  const app = Fastify({
    logger: {
      level: "info",
      redact: {
        paths: [
          "req.headers.authorization",
          "req.headers.cookie",
          "req.body",
          "req.query",
          'res.headers["set-cookie"]',
        ],
        remove: true,
      },
      serializers: {
        req: (req) => ({ method: req.method, url: req.url?.split("?")[0] }),
        err: () => ({ type: "Error", message: "Request failed", stack: "" }),
      },
    },
    disableRequestLogging: true,
    bodyLimit: 600000,
    trustProxy: "127.0.0.1",
  }).withTypeProvider<ZodTypeProvider>();
  const vault = new Vault(c);
  const auth = new Auth(c, db, vault);
  const service = new Service(db, vault, c.origin);
  const oa = oauth(c, db, vault);
  app.setValidatorCompiler(validatorCompiler);
  app.setSerializerCompiler(serializerCompiler);
  await app.register(cookie);
  await app.register(helmet, {
    crossOriginOpenerPolicy: { policy: "same-origin-allow-popups" },
    contentSecurityPolicy: {
      directives: {
        defaultSrc: ["'self'"],
        scriptSrc: ["'self'", "https://apis.google.com"],
        styleSrc: ["'self'", "'unsafe-inline'"],
        connectSrc: [
          "'self'",
          "https://*.googleapis.com",
          "https://*.firebaseapp.com",
          "https://*.google.com",
        ],
        frameSrc: ["https://*.firebaseapp.com", "https://accounts.google.com"],
        imgSrc: ["'self'", "data:", "https://lh3.googleusercontent.com"],
        objectSrc: ["'none'"],
        baseUri: ["'self'"],
      },
    },
  });
  await app.register(cors, { origin: c.origin, credentials: true });
  await app.register(rateLimit, { max: 120, timeWindow: "1 minute" });
  await app.register(swagger, {
    openapi: { info: { title: "TextKeep API", version: "1.0.0" } },
    transform: jsonSchemaTransform,
  });
  await app.register(swaggerUi, { routePrefix: "/api/docs" });
  app.setErrorHandler((e, req, reply) => {
    const error = e as { statusCode?: number; message: string };
    const status = Number(error.statusCode) || 500;
    reply
      .code(status)
      .send({ error: status >= 500 ? "Service unavailable" : error.message });
  });
  app.addHook("onRequest", async (req, reply) => {
    if (
      req.url.startsWith("/api/v1/") ||
      req.url.startsWith("/mcp") ||
      req.url.startsWith("/oauth") ||
      req.url.startsWith("/app/access/")
    ) {
      reply.header("Cache-Control", "no-store");
      reply.raw.setHeader("Cache-Control", "no-store");
    }
    if (
      ["POST", "PUT", "DELETE", "PATCH"].includes(req.method) &&
      req.cookies?.session &&
      req.url.startsWith("/api/v1/")
    )
      auth.csrf(req);
  });
  const identity = (req: Parameters<Auth["identity"]>[0]) => auth.identity(req);
  const owner = async (
    req: Parameters<Auth["identity"]>[0],
    recent = false,
  ) => {
    const i = await identity(req);
    auth.owner(i);
    if (recent) auth.recent(i);
    return i;
  };
  app.get("/health/live", async () => ({ ok: true }));
  app.get("/health/ready", async () => {
    await db.query("SELECT 1 FROM textkeep.schema_migrations LIMIT 1");
    return { ok: true };
  });
  app.get("/api/v1/public/config", async () => ({
    firebase: c.firebaseWeb,
    release: c.releaseMetadata || null,
    consentVersion: "2026-10-14",
    registration: c.registrationMode,
  }));
  app.get("/api/v1/auth/csrf", async (req, reply) => {
    const token = /^[A-Za-z0-9_-]{43}$/.test(req.cookies.csrf || "")
      ? req.cookies.csrf!
      : randomBytes(32).toString("base64url");
    reply.setCookie("csrf", token, {
      secure: c.production,
      httpOnly: true,
      sameSite: "strict",
      path: "/",
    });
    return { token };
  });
  app.post(
    "/api/v1/auth/session",
    {
      schema: {
        body: z.object({ idToken: z.string().min(20).max(10000) }).strict(),
      },
      config: { rateLimit: { max: 10 } },
    },
    async (req, reply) => {
      auth.csrf(req);
      const decoded = await auth.firebase.verifyIdToken(req.body.idToken, true);
      const i = await auth.bind(decoded);
      auth.recent(i);
      const session = await auth.firebase.createSessionCookie(
        req.body.idToken,
        { expiresIn: 3600000 },
      );
      reply.setCookie("session", session, {
        httpOnly: true,
        secure: c.production,
        sameSite: "lax",
        path: "/",
        maxAge: 3600,
      });
      return { ok: true };
    },
  );
  app.delete("/api/v1/auth/session", async (req, reply) => {
    await owner(req);
    await auth.revokeSession(req.cookies.session);
    reply.clearCookie("session", { path: "/" });
    return { ok: true };
  });
  app.get("/api/v1/me", async (req) => {
    const i = await owner(req);
    return {
      uid: i.uid,
      recent: Date.now() / 1000 - i.authTime < 300,
      scopes: ["messages:read", "messages:sensitive"],
    };
  });
  app.post(
    "/api/v1/devices",
    {
      schema: {
        body: z
          .object({
            name: z.string().min(1).max(80),
            consentVersion: z.literal("2026-10-14"),
          })
          .strict(),
      },
    },
    async (req) => {
      const i = await owner(req, true);
      const id = randomUUID(),
        token = "dev_" + randomBytes(32).toString("base64url");
      await db.query(
        "INSERT INTO textkeep.devices(id,owner,name,token_hash) VALUES($1,$2,$3,$4)",
        [id, i.uid, req.body.name, secretHash(token)],
      );
      return { id, token };
    },
  );
  app.get("/api/v1/devices", async (req) => {
    const i = await owner(req);
    return (await service.status(i)).devices;
  });
  app.post(
    "/api/v1/devices/:id/revoke",
    { schema: { params: z.object({ id: z.string().uuid() }) } },
    async (req) => {
      const i = await owner(req, true);
      const r = await db.query(
        "UPDATE textkeep.devices SET revoked_at=now() WHERE owner=$1 AND id=$2",
        [i.uid, req.params.id],
      );
      return { revoked: r.rowCount === 1 };
    },
  );
  app.post(
    "/api/v1/sync/batches",
    { schema: { body: syncBatch }, config: { rateLimit: { max: 30 } } },
    async (req) => {
      const i = await identity(req);
      await auth.device(
        i,
        req.body.deviceId,
        String(req.headers["x-device-token"] || ""),
      );
      return {
        acknowledgements: await service.ingest(
          i,
          req.body.deviceId,
          req.body.batchId,
          req.body.records,
        ),
      };
    },
  );
  app.get("/api/v1/sync/status", async (req) =>
    service.status(await owner(req)),
  );
  app.get("/api/v1/sync/history", async (req) => {
    const i = await owner(req);
    const r = await db.query(
      "SELECT b.device,b.created_at,jsonb_array_length(b.acknowledgements) AS processed,d.name FROM textkeep.ingestion_batches b JOIN textkeep.devices d ON d.id=b.device AND d.owner=b.owner WHERE b.owner=$1 ORDER BY b.created_at DESC LIMIT 100",
      [i.uid],
    );
    return { uploads: r.rows, truncated: r.rowCount === 100 };
  });
  app.get(
    "/api/v1/messages",
    {
      schema: { querystring: searchQuery },
      config: { rateLimit: { max: 60 } },
    },
    async (req) => service.search(await owner(req), req.query),
  );
  app.get(
    "/api/v1/messages/:id",
    { schema: { params: z.object({ id: z.string().uuid() }) } },
    async (req) => service.get(await owner(req), req.params.id),
  );
  app.get("/api/v1/threads", async (req) => service.threads(await owner(req)));
  app.get(
    "/api/v1/threads/:id/messages",
    {
      schema: {
        params: z.object({ id: z.string().max(512) }),
        querystring: searchQuery,
      },
    },
    async (req) => service.thread(await owner(req), req.params.id, req.query),
  );
  app.post(
    "/api/v1/messages/purge",
    {
      schema: {
        body: z
          .object({
            ids: z.array(z.string().uuid()).min(1).max(500),
            confirmation: z.literal("DELETE SERVER COPIES"),
          })
          .strict(),
      },
    },
    async (req) => service.purge(await owner(req, true), req.body.ids),
  );
  app.get("/api/v1/access", async (req) => {
    const i = await owner(req);
    return {
      grants: await oa.grants(i),
      tokens: (
        await db.query(
          "SELECT id,name,sensitive,expires_at,revoked_at FROM textkeep.personal_access_tokens WHERE owner=$1",
          [i.uid],
        )
      ).rows,
    };
  });
  app.post(
    "/api/v1/personal-access-tokens",
    {
      schema: {
        body: z
          .object({
            name: z.string().min(1).max(80),
            sensitive: z.boolean().default(false),
            days: z.number().int().min(1).max(30).default(7),
          })
          .strict(),
      },
    },
    async (req) => {
      const i = await owner(req, true);
      const token = "tk_" + randomBytes(32).toString("base64url"),
        id = randomUUID();
      await db.query(
        "INSERT INTO textkeep.personal_access_tokens(id,owner,name,hash,sensitive,expires_at) VALUES($1,$2,$3,$4,$5,$6)",
        [
          id,
          i.uid,
          req.body.name,
          secretHash(token),
          req.body.sensitive,
          new Date(Date.now() + req.body.days * 86400000),
        ],
      );
      return { id, token };
    },
  );
  app.delete(
    "/api/v1/personal-access-tokens/:id",
    { schema: { params: z.object({ id: z.string().uuid() }) } },
    async (req) => {
      const i = await owner(req, true);
      await db.query(
        "UPDATE textkeep.personal_access_tokens SET revoked_at=now() WHERE owner=$1 AND id=$2",
        [i.uid, req.params.id],
      );
      return { ok: true };
    },
  );
  app.post(
    "/api/v1/access/:id/revoke",
    {
      schema: { params: z.object({ id: z.string().regex(/^[a-f0-9]{64}$/) }) },
    },
    async (req) => {
      await oa.revoke(await owner(req, true), req.params.id);
      return { ok: true };
    },
  );
  app.get("/.well-known/oauth-protected-resource/mcp", async () => ({
    resource: `${c.origin}/mcp`,
    authorization_servers: [`${c.origin}/oauth`],
    scopes_supported: ["messages:read", "messages:sensitive"],
    bearer_methods_supported: ["header"],
  }));
  app.get("/.well-known/oauth-authorization-server/oauth", async (req, reply) =>
    reply.redirect("/oauth/.well-known/oauth-authorization-server"),
  );
  app.get("/app/access/interaction", async (req, reply) => {
    await owner(req);
    const d = await oa.details(req.raw, reply.raw);
    const client = await oa.provider.Client.find(String(d.params.client_id));
    return {
      client: client?.clientName || d.params.client_id,
      clientId: d.params.client_id,
      prompt: d.prompt.name,
      requestedScopes: d.params.scope,
      resource: d.params.resource,
    };
  });
  app.post(
    "/app/access/consent",
    {
      schema: {
        body: z
          .object({
            sensitive: z.boolean().default(false),
            approve: z.literal(true),
          })
          .strict(),
      },
    },
    async (req, reply) => {
      auth.csrf(req);
      const i = await owner(req, true);
      const redirectTo = await oa.consent(
        req.raw,
        reply.raw,
        i,
        req.body.sensitive,
      );
      // Resume OAuth as a top-level navigation. Fetch must never follow the
      // eventual cross-origin client callback (including a native loopback URI).
      return { redirectTo };
    },
  );
  await app.register(async (raw) => {
    raw.removeAllContentTypeParsers();
    raw.addContentTypeParser("*", (_req, payload, done) => done(null, payload));
    raw.all(
      "/oauth/*",
      { config: { rateLimit: { max: 30 } } },
      async (req, reply) => {
        // The provider derives its external mount prefix from originalUrl.
        // Preserve it so discovery and interaction redirects include /oauth.
        const request = req.raw as typeof req.raw & { originalUrl?: string };
        request.originalUrl = request.url;
        request.url = request.url?.replace(/^\/oauth/, "") || "/";
        reply.hijack();
        try {
          await oa.provider.callback()(request, reply.raw);
        } finally {
          request.url = request.originalUrl;
          delete request.originalUrl;
        }
      },
    );
  });
  const mcpIdentity = async (req: Parameters<Auth["identity"]>[0]) => {
    const token = req.headers.authorization?.replace(/^Bearer /, "");
    if (!token) throw new HttpError(401, "Authentication required");
    if (token.startsWith("tk_")) return identity(req);
    return oa.identity(token);
  };
  app.post(
    "/mcp",
    { config: { rateLimit: { max: 30 } } },
    async (req, reply) => {
      let i: Identity;
      try {
        i = await mcpIdentity(req);
      } catch {
        reply.header(
          "WWW-Authenticate",
          `Bearer resource_metadata="${c.origin}/.well-known/oauth-protected-resource/mcp"`,
        );
        throw new HttpError(401, "MCP authorization required");
      }
      if (req.headers.origin && req.headers.origin !== c.origin)
        throw new HttpError(403, "Forbidden origin");
      const server = mcpServer(service, i);
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined,
        enableJsonResponse: true,
      });
      reply.raw.on("close", () => {
        void transport.close();
        void server.close();
      });
      reply.hijack();
      await server.connect(transport);
      await transport.handleRequest(req.raw, reply.raw, req.body);
    },
  );
  app.get("/mcp", async (req, reply) => {
    await mcpIdentity(req);
    return reply.code(405).send({ error: "Use Streamable HTTP POST" });
  });
  app.get("/downloads/textkeep.apk", async (req, reply) => {
    if (!c.releaseMetadata || !c.apkPath || !existsSync(c.apkPath))
      return reply.code(404).send({ error: "Release not available" });
    reply
      .header("Content-Type", "application/vnd.android.package-archive")
      .header(
        "Content-Disposition",
        `attachment; filename="textkeep-${(c.releaseMetadata as { version: string }).version}.apk"`,
      );
    return reply.send(createReadStream(c.apkPath));
  });
  const web = resolve(process.cwd(), "apps/web/dist");
  if (existsSync(web)) {
    await app.register(staticPlugin, { root: web, prefix: "/" });
    app.setNotFoundHandler((req, reply) => {
      if (
        /^\/(api|mcp|oauth|health|\.well-known|downloads)(\/|$)/.test(req.url)
      )
        return reply.code(404).send({ error: "Not found" });
      return reply.sendFile("index.html");
    });
  }
  app.addHook("onClose", async () => {
    await db.end();
  });
  return app;
}
