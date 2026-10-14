import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
const paths = execFileSync(
  "git",
  ["ls-files", "--cached", "--others", "--exclude-standard", "-z"],
  { encoding: "utf8" },
)
  .split("\0")
  .filter(Boolean);
const forbidden =
  /(^|\/)(\.env(?:\.[^/]+)?|google-services(?:\.[^/]+)?\.json|firebase-admin\.json|deployment-state\.json)$|\.(jks|keystore|p12|pem|key)$/;
const patterns = [
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /gh[pousr]_[A-Za-z0-9]{20,}/,
  /github_pat_[A-Za-z0-9_]{20,}/,
  /AIza[\w-]{30,}/,
  ...(process.env.SECRET_SCAN_DENY || "")
    .split(",")
    .filter(Boolean)
    .map((s) => new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))),
];
let failed = false;
for (const path of new Set(paths)) {
  if (path === ".env.example") continue;
  if (forbidden.test(path) || path.startsWith(".private/")) {
    console.error("Forbidden public file:", path);
    failed = true;
    continue;
  }
  const data = readFileSync(path);
  if (data.includes(0)) continue;
  const text = data.toString();
  if (patterns.some((p) => p.test(text))) {
    console.error("Sensitive configuration pattern in:", path);
    failed = true;
  }
}
let commits = [];
try {
  commits = execFileSync("git", ["rev-list", "--all"], { encoding: "utf8" })
    .trim()
    .split("\n")
    .filter(Boolean);
} catch {}
for (const commit of commits) {
  const patch = execFileSync(
    "git",
    ["show", "--format=", "--no-ext-diff", commit],
    { encoding: "utf8", maxBuffer: 25 * 1024 * 1024 },
  );
  if (patterns.some((p) => p.test(patch))) {
    console.error("Sensitive pattern in history commit:", commit);
    failed = true;
  }
}
if (failed) process.exit(1);
console.log(
  `PASS: public source and ${commits.length} history commits checked; no private deployment configuration or credential patterns found.`,
);
