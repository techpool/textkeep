import {
  createCipheriv,
  createDecipheriv,
  randomBytes,
  createHmac,
  createHash,
  timingSafeEqual,
} from "node:crypto";
import type { Config } from "./config.js";
export function secretHash(token: string) {
  return createHash("sha256").update(token).digest("hex");
}
export function equal(a: string, b: string) {
  const aa = Buffer.from(a),
    bb = Buffer.from(b);
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
export class Vault {
  constructor(private c: Pick<Config, "keyring" | "activeKey" | "lookupKey">) {}
  seal(value: unknown, context: string) {
    const nonce = randomBytes(12);
    const cipher = createCipheriv(
      "aes-256-gcm",
      Buffer.from(this.c.keyring[this.c.activeKey]!, "base64"),
      nonce,
    );
    cipher.setAAD(Buffer.from(context));
    const data = Buffer.concat([
      cipher.update(JSON.stringify(value), "utf8"),
      cipher.final(),
    ]);
    return [
      this.c.activeKey,
      nonce.toString("base64url"),
      cipher.getAuthTag().toString("base64url"),
      data.toString("base64url"),
    ].join(".");
  }
  open<T>(value: string, context: string): T {
    const [key, nonce, tag, data] = value.split(".");
    if (!key || !this.c.keyring[key] || !nonce || !tag || !data)
      throw Error("Encrypted record unavailable");
    const cipher = createDecipheriv(
      "aes-256-gcm",
      Buffer.from(this.c.keyring[key]!, "base64"),
      Buffer.from(nonce, "base64url"),
    );
    cipher.setAAD(Buffer.from(context));
    cipher.setAuthTag(Buffer.from(tag, "base64url"));
    return JSON.parse(
      Buffer.concat([
        cipher.update(Buffer.from(data, "base64url")),
        cipher.final(),
      ]).toString(),
    ) as T;
  }
  lookup(value: string) {
    return createHmac("sha256", Buffer.from(this.c.lookupKey, "base64"))
      .update(value)
      .digest("hex");
  }
}
export function sensitive(body: string, otp?: string | null) {
  return (
    !!otp ||
    /\b(?:otp|one[ -]time|verification|security code|password|recovery|reset|login code|authentication|passcode)\b|ओटीपी|ওটিপি|সত্যাপন|सत्यापन/i.test(
      body,
    )
  );
}
export function redactSensitive(body: string) {
  return body
    .replace(/https?:\/\/\S+/gi, "[link omitted]")
    .replace(/\b[\w-]{16,}\b/g, "[value omitted]")
    .replace(/\b\d(?:[ -]?\d){3,}\b/g, "[number omitted]");
}
