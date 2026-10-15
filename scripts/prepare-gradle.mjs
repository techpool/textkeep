import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, renameSync, rmSync } from "node:fs";
import { join } from "node:path";

// RunnerMesh gives each build a private Gradle home. Use curl for the initial
// download because Java's downloader cannot reach the redirect host on some
// runners. The wrapper still verifies and installs the official distribution.
// RunnerMesh 0.8 initializes this variable after preBuild hooks. Its private
// cache is a sibling of the checkout; the hook supplies that path explicitly.
const home = process.env.GRADLE_USER_HOME || process.argv[2];
if (!home) throw Error("A build-specific GRADLE_USER_HOME is required");
const properties = readFileSync(
  new URL(
    "../apps/android/gradle/wrapper/gradle-wrapper.properties",
    import.meta.url,
  ),
  "utf8",
);
const url = properties
  .match(/^distributionUrl=(.+)$/m)?.[1]
  .replaceAll("\\:", ":");
const checksum = properties.match(
  /^distributionSha256Sum=([a-f0-9]{64})$/m,
)?.[1];
if (
  !url ||
  !/^https:\/\/services\.gradle\.org\/distributions\/gradle-[\d.]+-bin\.zip$/.test(
    url,
  ) ||
  !checksum
)
  throw Error("Expected a checksum-pinned official Gradle distribution");
// Gradle PathAssembler uses an MD5-derived base-36 directory name. This is a
// cache identifier; SHA-256 below supplies the integrity check.
const key = BigInt("0x" + createHash("md5").update(url).digest("hex")).toString(
  36,
);
const name = new URL(url).pathname.split("/").at(-1);
const directory = join(home, "wrapper", "dists", name.slice(0, -4), key);
mkdirSync(directory, { recursive: true, mode: 0o700 });
const destination = join(directory, name);
const temporary = destination + ".download";
try {
  execFileSync(
    "curl",
    [
      "--fail",
      "--silent",
      "--show-error",
      "--location",
      "--proto",
      "=https",
      "--proto-redir",
      "=https",
      "--connect-timeout",
      "15",
      "--max-time",
      "180",
      "--retry",
      "2",
      "--output",
      temporary,
      url,
    ],
    { stdio: "inherit" },
  );
  if (
    createHash("sha256").update(readFileSync(temporary)).digest("hex") !==
    checksum
  )
    throw Error("Official Gradle archive checksum mismatch");
  renameSync(temporary, destination);
  console.log(
    "PASS: official Gradle archive cached with pinned SHA-256 verification",
  );
} finally {
  rmSync(temporary, { force: true });
}
