const origin = process.argv[2];
if (!origin?.startsWith("https://"))
  throw Error("Supply the deployed HTTPS origin");
for (const route of [
  "/",
  "/privacy",
  "/self-host",
  "/health/live",
  "/health/ready",
  "/.well-known/oauth-protected-resource/mcp",
  "/.well-known/oauth-authorization-server/oauth",
]) {
  const r = await fetch(origin + route);
  if (!r.ok) throw Error(`${route}: ${r.status}`);
  console.log("PASS", route, r.status);
}
for (const route of [
  "/api/v1/me",
  "/api/v1/messages",
  "/api/v1/devices",
  "/api/v1/threads",
  "/api/v1/sync/history",
  "/mcp",
]) {
  const r = await fetch(origin + route, {
    method: route === "/mcp" ? "POST" : "GET",
    headers: route === "/mcp" ? { "Content-Type": "application/json" } : {},
    body: route === "/mcp" ? "{}" : undefined,
  });
  if (r.status !== 401)
    throw Error(`Expected authentication denial at ${route}: ${r.status}`);
  console.log("PASS authentication required", route);
}
const metadata = await (
  await fetch(origin + "/oauth/.well-known/oauth-authorization-server")
).json();
if (metadata.issuer !== origin + "/oauth")
  throw Error("Unexpected OAuth issuer");
for (const key of [
  "authorization_endpoint",
  "registration_endpoint",
  "token_endpoint",
  "revocation_endpoint",
  "jwks_uri",
])
  if (!metadata[key]?.startsWith(origin + "/oauth/"))
    throw Error(`OAuth ${key} lacks its mount prefix`);
console.log("PASS OAuth discovery mount prefix");
