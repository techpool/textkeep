import proxyaddr from "@fastify/proxy-addr";
import { isIP } from "node:net";
import type { FastifyRequest } from "fastify";

export function proxyRanges(raw: string): string[] {
  const ranges = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  for (const range of ranges) {
    const [address, prefix, extra] = range.split("/");
    if (
      !address ||
      !isIP(address) ||
      extra !== undefined ||
      (prefix !== undefined && (!/^\d+$/.test(prefix) || Number(prefix) === 0))
    )
      throw Error(
        "Proxy trust requires explicit IP addresses or nonzero CIDRs",
      );
  }
  proxyaddr.compile(ranges);
  return ranges;
}

export function rateLimitAddress(
  trustedProxies: string[],
  cloudflareProxies: string[],
) {
  const originProxy = proxyaddr.compile(trustedProxies);
  const cloudflare = proxyaddr.compile(cloudflareProxies);
  return (req: FastifyRequest): string => {
    const peer = req.raw.socket.remoteAddress;
    const client = req.headers["cf-connecting-ip"];
    // Fastify stops its forwarded chain at the first untrusted hop. Cloudflare
    // is deliberately not in trustedProxies: req.ip must be its actual edge IP.
    // Trust its overwritten header only through the configured origin proxy.
    if (
      peer &&
      originProxy(peer, 0) &&
      cloudflare(req.ip, 0) &&
      typeof client === "string" &&
      isIP(client)
    )
      return client;
    return req.ip;
  };
}
