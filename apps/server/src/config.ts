import { readFileSync } from "node:fs";
export interface Config {
  production: boolean;
  origin: string;
  databaseUrl: string;
  firebaseProject: string;
  allowedEmails: string[];
  ownerUid?: string;
  keyring: Record<string, string>;
  activeKey: string;
  lookupKey: string;
  firebaseWeb: Record<string, string>;
  oauthJwks: object;
  port: number;
  apkPath?: string;
  releaseMetadata?: object;
}
export function config(env: NodeJS.ProcessEnv = process.env): Config {
  const required = (name: string) => {
    const value = env[name];
    if (!value) throw Error(`Missing required configuration: ${name}`);
    return value;
  };
  const production = env.APP_ENV === "production";
  const origin = required("PUBLIC_BASE_URL").replace(/\/$/, "");
  const keyring = JSON.parse(required("MESSAGE_ENCRYPTION_KEYRING")) as Record<
    string,
    string
  >;
  const activeKey = required("ACTIVE_MESSAGE_KEY_ID");
  if (
    !keyring[activeKey] ||
    Object.values(keyring).some((k) => Buffer.from(k, "base64").length !== 32)
  )
    throw Error("Message encryption keys must be 32 bytes");
  const allowedEmails = required("ALLOWED_GOOGLE_EMAILS")
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
  if (!allowedEmails.length || allowedEmails.includes("*"))
    throw Error("Explicit account allowlist required");
  if (
    production &&
    (!origin.startsWith("https://") ||
      env.FIREBASE_AUTH_EMULATOR_HOST ||
      env.DEBUG_AUTH ||
      env.CORS_ORIGIN === "*")
  )
    throw Error("Unsafe production authentication configuration");
  const lookupKey = required("LOOKUP_HMAC_KEY");
  if (Buffer.from(lookupKey, "base64").length < 32)
    throw Error("Lookup key must be at least 32 bytes");
  return {
    production,
    origin,
    databaseUrl: required("DATABASE_URL"),
    firebaseProject: required("FIREBASE_PROJECT_ID"),
    allowedEmails,
    ownerUid: env.OWNER_FIREBASE_UID,
    keyring,
    activeKey,
    lookupKey,
    firebaseWeb: JSON.parse(required("FIREBASE_WEB_CONFIG")),
    oauthJwks: JSON.parse(required("OAUTH_JWKS")),
    port: Number(env.PORT || 3000),
    apkPath: env.APK_PATH,
    releaseMetadata: env.RELEASE_METADATA_PATH
      ? JSON.parse(readFileSync(env.RELEASE_METADATA_PATH, "utf8"))
      : undefined,
  };
}
