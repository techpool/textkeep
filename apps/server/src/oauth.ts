import Provider, {
  type Configuration,
  type AdapterPayload,
} from "oidc-provider";
import type pg from "pg";
import type { Config } from "./config.js";
import { Vault, secretHash } from "./crypto.js";
import { HttpError, type Identity, Auth } from "./auth.js";
export function oauth(c: Config, db: pg.Pool, vault: Vault) {
  class Adapter {
    constructor(private model: string) {}
    async upsert(id: string, payload: AdapterPayload, expiresIn: number) {
      await db.query(
        "INSERT INTO textkeep.oauth_objects(model,id,payload,grant_id,user_code,uid,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(model,id) DO UPDATE SET payload=EXCLUDED.payload,grant_id=EXCLUDED.grant_id,user_code=EXCLUDED.user_code,uid=EXCLUDED.uid,expires_at=EXCLUDED.expires_at",
        [
          this.model,
          secretHash(id),
          vault.seal(payload, `oauth:${this.model}:${secretHash(id)}`),
          payload.grantId || null,
          payload.userCode || null,
          payload.uid || null,
          expiresIn ? new Date(Date.now() + expiresIn * 1000) : null,
        ],
      );
    }
    async find(id: string) {
      const r = await db.query(
        "SELECT * FROM textkeep.oauth_objects WHERE model=$1 AND id=$2 AND (expires_at IS NULL OR expires_at>now())",
        [this.model, secretHash(id)],
      );
      const row = r.rows[0];
      if (!row) return undefined;
      const payload = vault.open<AdapterPayload>(
        row.payload,
        `oauth:${this.model}:${row.id}`,
      );
      if (row.consumed_at)
        payload.consumed = Math.floor(
          new Date(row.consumed_at).getTime() / 1000,
        );
      return payload;
    }
    async findByUserCode(code: string) {
      const r = await db.query(
        "SELECT id FROM textkeep.oauth_objects WHERE model=$1 AND user_code=$2",
        [this.model, code],
      );
      const row = r.rows[0];
      return row ? this.findHashed(row.id) : undefined;
    }
    async findByUid(uid: string) {
      const r = await db.query(
        "SELECT id FROM textkeep.oauth_objects WHERE model=$1 AND uid=$2",
        [this.model, uid],
      );
      const row = r.rows[0];
      return row ? this.findHashed(row.id) : undefined;
    }
    async findHashed(id: string) {
      const r = await db.query(
        "SELECT * FROM textkeep.oauth_objects WHERE model=$1 AND id=$2 AND (expires_at IS NULL OR expires_at>now())",
        [this.model, id],
      );
      const row = r.rows[0];
      if (!row) return undefined;
      const p = vault.open<AdapterPayload>(
        row.payload,
        `oauth:${this.model}:${id}`,
      );
      if (row.consumed_at)
        p.consumed = Math.floor(new Date(row.consumed_at).getTime() / 1000);
      return p;
    }
    async consume(id: string) {
      await db.query(
        "UPDATE textkeep.oauth_objects SET consumed_at=now() WHERE model=$1 AND id=$2",
        [this.model, secretHash(id)],
      );
    }
    async destroy(id: string) {
      await db.query(
        "DELETE FROM textkeep.oauth_objects WHERE model=$1 AND id=$2",
        [this.model, secretHash(id)],
      );
    }
    async revokeByGrantId(grantId: string) {
      await db.query(
        "DELETE FROM textkeep.oauth_objects WHERE grant_id=$1 OR (model=$2 AND id=$3)",
        [grantId, "Grant", secretHash(grantId)],
      );
    }
  }
  const configuration: Configuration = {
    adapter: Adapter,
    clients: [],
    jwks: c.oauthJwks as Configuration["jwks"],
    cookies: {
      keys: [vault.lookup("oauth-cookie-v1")],
      long: { signed: true, secure: c.production, sameSite: "lax" },
      short: { signed: true, secure: c.production, sameSite: "lax" },
    },
    // API permissions belong to the MCP resource, not OIDC identity claims.
    scopes: ["openid", "offline_access"],
    responseTypes: ["code"],
    pkce: { required: () => true },
    rotateRefreshToken: () => true,
    ttl: {
      AccessToken: 600,
      RefreshToken: 86400,
      AuthorizationCode: 60,
      Interaction: 600,
      Grant: 86400 * 30,
      Session: 3600,
    },
    features: {
      devInteractions: { enabled: false },
      registration: { enabled: true },
      revocation: { enabled: true },
      resourceIndicators: {
        enabled: true,
        defaultResource: () => `${c.origin}/mcp`,
        useGrantedResource: () => true,
        getResourceServerInfo: (_ctx, resource) => {
          if (resource !== `${c.origin}/mcp`)
            throw new HttpError(400, "Invalid MCP resource");
          return {
            scope: "messages:read messages:sensitive",
            audience: resource,
            accessTokenFormat: "opaque",
          };
        },
      },
    },
    interactions: {
      url: (_ctx, interaction) =>
        `${c.origin}/app/access?interaction=${encodeURIComponent(interaction.uid)}`,
    },
    async findAccount(_ctx, id) {
      const r = await db.query(
        "SELECT uid FROM textkeep.users WHERE uid=$1 AND disabled=false",
        [id],
      );
      if (!r.rowCount) return undefined;
      return {
        accountId: id,
        async claims() {
          return { sub: id };
        },
      };
    },
    clientBasedCORS: (_ctx, origin) => origin === c.origin,
  };
  const provider = new Provider(`${c.origin}/oauth`, configuration);
  provider.proxy = true;
  provider.on("server_error", () => {});
  return {
    provider,
    async identity(token: string): Promise<Identity> {
      const at = await provider.AccessToken.find(token);
      if (
        !at ||
        at.isExpired ||
        at.aud !== `${c.origin}/mcp` ||
        !at.scope?.split(" ").includes("messages:read") ||
        !at.accountId ||
        !at.grantId
      )
        throw new HttpError(401, "Invalid MCP access token");
      const activeOwner = await db.query(
        "SELECT uid FROM textkeep.users WHERE uid=$1 AND disabled=false",
        [at.accountId],
      );
      if (!activeOwner.rowCount || !(await provider.Grant.find(at.grantId)))
        throw new HttpError(401, "Grant revoked");
      return {
        uid: at.accountId,
        authTime: 0,
        client: at.clientId || "unknown",
        sensitive: at.scope.split(" ").includes("messages:sensitive"),
        kind: "oauth",
      };
    },
    async consent(
      req: Parameters<Provider["interactionDetails"]>[0],
      res: Parameters<Provider["interactionDetails"]>[1],
      i: Identity,
      allowSensitive: boolean,
    ) {
      const details = await provider.interactionDetails(req, res);
      const { uid, prompt, params, session } = details;
      if (prompt.name === "login") {
        return await provider.interactionResult(
          req,
          res,
          { login: { accountId: i.uid, remember: false } },
          { mergeWithLastSubmission: false },
        );
      }
      if (prompt.name !== "consent")
        throw new HttpError(400, "Unsupported interaction");
      if (session?.accountId !== i.uid)
        throw new HttpError(403, "Interaction owner mismatch");
      const grant = new provider.Grant({
        accountId: i.uid,
        clientId: String(params.client_id),
      });
      grant.addOIDCScope("openid offline_access");
      grant.addResourceScope(
        `${c.origin}/mcp`,
        allowSensitive ? "messages:read messages:sensitive" : "messages:read",
      );
      await grant.save();
      return await provider.interactionResult(
        req,
        res,
        { consent: { grantId: grant.jti } },
        { mergeWithLastSubmission: true },
      );
    },
    async grants(i: Identity) {
      const r = await db.query(
        "SELECT payload,id FROM textkeep.oauth_objects WHERE model='Grant' AND (expires_at IS NULL OR expires_at>now())",
      );
      return r.rows
        .map((row) => ({
          hash: row.id,
          payload: vault.open<AdapterPayload>(
            row.payload,
            `oauth:Grant:${row.id}`,
          ),
        }))
        .filter((row) => row.payload.accountId === i.uid)
        .map((row) => ({
          id: row.hash,
          client: row.payload.clientId,
          scope: row.payload.resources,
        }));
    },
    async revoke(i: Identity, hash: string) {
      const r = await db.query(
        "SELECT payload FROM textkeep.oauth_objects WHERE model='Grant' AND id=$1",
        [hash],
      );
      if (!r.rowCount) throw new HttpError(404, "Grant unavailable");
      const p = vault.open<AdapterPayload>(
        r.rows[0].payload,
        `oauth:Grant:${hash}`,
      );
      if (p.accountId !== i.uid) throw new HttpError(404, "Grant unavailable");
      await new Adapter("Grant").revokeByGrantId(p.jti!);
    },
  };
}
