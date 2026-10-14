import { initializeApp, applicationDefault, getApps } from "firebase-admin/app";
import { getAuth, type DecodedIdToken } from "firebase-admin/auth";
import type { FastifyRequest } from "fastify";
import type pg from "pg";
import { randomUUID } from "node:crypto";
import type { Config } from "./config.js";
import { Vault, secretHash, equal } from "./crypto.js";
export class HttpError extends Error {
  constructor(
    public statusCode: number,
    message: string,
  ) {
    super(message);
  }
}
export interface Identity {
  uid: string;
  authTime: number;
  client: string;
  sensitive: boolean;
  kind: "firebase" | "session" | "pat" | "oauth";
}
export class Auth {
  readonly firebase;
  constructor(
    private c: Config,
    private db: pg.Pool,
    private vault: Vault,
  ) {
    const app =
      getApps()[0] ||
      initializeApp({
        projectId: c.firebaseProject,
        credential: applicationDefault(),
      });
    this.firebase = getAuth(app);
  }
  async bind(token: DecodedIdToken): Promise<Identity> {
    if (
      !token.email_verified ||
      token.firebase.sign_in_provider !== "google.com" ||
      !token.email ||
      !this.c.allowedEmails.includes(token.email.toLowerCase()) ||
      (this.c.ownerUid && token.uid !== this.c.ownerUid)
    )
      throw new HttpError(403, "Account is not authorized");
    const row = await this.db.query(
      "INSERT INTO textkeep.users(uid,email_digest,consent_version) VALUES($1,$2,$3) ON CONFLICT(uid) DO UPDATE SET uid=EXCLUDED.uid RETURNING disabled",
      [token.uid, this.vault.lookup(token.email.toLowerCase()), "2026-10-14"],
    );
    if (row.rows[0]?.disabled) throw new HttpError(403, "Account disabled");
    return {
      uid: token.uid,
      authTime: token.auth_time,
      client: "owner",
      sensitive: true,
      kind: "firebase",
    };
  }
  async identity(
    req: Pick<FastifyRequest, "headers" | "cookies">,
  ): Promise<Identity> {
    const header = req.headers.authorization;
    if (header?.startsWith("Bearer tk_")) {
      const r = await this.db.query(
        "SELECT t.* FROM textkeep.personal_access_tokens t JOIN textkeep.users u ON u.uid=t.owner WHERE t.hash=$1 AND t.revoked_at IS NULL AND t.expires_at>now() AND u.disabled=false",
        [secretHash(header.slice(7))],
      );
      const t = r.rows[0];
      if (!t) throw new HttpError(401, "Invalid access token");
      return {
        uid: t.owner,
        authTime: 0,
        client: t.id,
        sensitive: t.sensitive,
        kind: "pat",
      };
    }
    if (header?.startsWith("Bearer ")) {
      try {
        return await this.bind(
          await this.firebase.verifyIdToken(header.slice(7), true),
        );
      } catch (e) {
        if (e instanceof HttpError) throw e;
        throw new HttpError(401, "Sign in again");
      }
    }
    const session = req.cookies?.session;
    if (session) {
      try {
        const revoked = await this.db.query(
          "SELECT 1 FROM textkeep.revoked_web_sessions WHERE hash=$1 AND expires_at>now()",
          [secretHash(session)],
        );
        if (revoked.rowCount) throw new HttpError(401, "Sign in again");
        const identity = await this.bind(
          await this.firebase.verifySessionCookie(session, true),
        );
        identity.kind = "session";
        return identity;
      } catch (e) {
        if (e instanceof HttpError) throw e;
        throw new HttpError(401, "Sign in again");
      }
    }
    throw new HttpError(401, "Authentication required");
  }
  async revokeSession(session?: string) {
    if (!session) return;
    // A web cookie lives for one hour. Preserve phone refresh credentials and
    // independently consented MCP grants when this browser signs out.
    await this.db.query(
      "INSERT INTO textkeep.revoked_web_sessions(hash,expires_at) VALUES($1,now()+interval '1 hour') ON CONFLICT(hash) DO NOTHING",
      [secretHash(session)],
    );
    await this.db.query(
      "DELETE FROM textkeep.revoked_web_sessions WHERE expires_at<=now()",
    );
  }
  recent(i: Identity) {
    if (
      !["firebase", "session"].includes(i.kind) ||
      Date.now() / 1000 - i.authTime > 300
    )
      throw new HttpError(401, "Sign in again to confirm this action");
  }
  owner(i: Identity) {
    if (!["firebase", "session"].includes(i.kind))
      throw new HttpError(403, "Owner session required");
  }
  csrf(req: FastifyRequest) {
    if (req.headers.origin !== this.c.origin)
      throw new HttpError(403, "Forbidden origin");
    if (
      req.cookies?.session &&
      !equal(String(req.headers["x-csrf-token"] || ""), req.cookies.csrf || "")
    )
      throw new HttpError(403, "Invalid request confirmation");
  }
  async device(i: Identity, id: string, token: string) {
    if (i.kind !== "firebase")
      throw new HttpError(403, "Phone authentication required");
    const r = await this.db.query(
      "SELECT id FROM textkeep.devices WHERE owner=$1 AND id=$2 AND token_hash=$3 AND revoked_at IS NULL",
      [i.uid, id, secretHash(token)],
    );
    if (!r.rowCount) throw new HttpError(403, "Device authorization denied");
  }
}
