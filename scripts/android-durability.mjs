import { execFileSync } from "node:child_process";
import { join } from "node:path";
const serial = process.argv[2];
if (!serial?.startsWith("emulator-"))
  throw Error("Supply a disposable emulator serial, e.g. emulator-5554");
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
const adb = sdk ? join(sdk, "platform-tools", "adb") : "adb";
function command(args, timeout = 180000) {
  return execFileSync(adb, ["-s", serial, ...args], {
    encoding: "utf8",
    timeout,
    maxBuffer: 1024 * 1024,
  });
}
if (command(["shell", "getprop", "ro.boot.qemu"]).trim() !== "1")
  throw Error("Refusing to run on a physical phone");
const target = "io.github.techpool.textkeep.debug";
const suite = "io.github.techpool.textkeep.DurabilityTest";
function test(method, stage, className = suite) {
  const args = [
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "class",
    className + "#" + method,
  ];
  if (stage) args.push("-e", "durabilityStage", stage);
  args.push(target + ".test/androidx.test.runner.AndroidJUnitRunner");
  const output = command(args);
  if (
    !/OK \(1 test\)/.test(output) ||
    /FAILURES!!!|INSTRUMENTATION_FAILED/.test(output)
  ) {
    console.error(output);
    throw Error("Instrumentation failed: " + method);
  }
  console.log("PASS", method);
}
test("prepareCommittedJournalForRestart", "prepare");
command(["shell", "am", "force-stop", target]);
test("verifyCommittedJournalAfterRestart", "verify");
test("acknowledgementCannotEraseAnEditMadeDuringUpload");
test("mmsTextPduPreservesUnicodeAndHeaders");
if (process.argv.includes("--sms")) {
  const smsSuite = "io.github.techpool.textkeep.DefaultSmsTest";
  test("selectThroughNormalSystemRolePrompt", undefined, smsSuite);
  command(["shell", "svc", "wifi", "disable"]);
  command(["shell", "svc", "data", "disable"]);
  const body = Buffer.from(
    "Your OTP is 515253.\n@example.test #515253",
    "utf16le",
  );
  for (let i = 0; i < body.length; i += 2)
    [body[i], body[i + 1]] = [body[i + 1], body[i]];
  const now = new Date();
  const swapped = (n) => {
    const s = String(n).padStart(2, "0");
    return Number.parseInt(s[1] + s[0], 16);
  };
  const timestamp = Buffer.from(
    [
      now.getUTCFullYear() % 100,
      now.getUTCMonth() + 1,
      now.getUTCDate(),
      now.getUTCHours(),
      now.getUTCMinutes(),
      now.getUTCSeconds(),
      0,
    ].map(swapped),
  );
  // SMS-DELIVER, synthetic sender 40404, UCS-2, no multipart header. Encode the
  // newline in the PDU rather than sending it as an emulator console command.
  const pdu = Buffer.concat([
    Buffer.from([0, 4, 5, 0x81, 0x04, 0x04, 0xf4, 0, 8]),
    timestamp,
    Buffer.from([body.length]),
    body,
  ]);
  const sent = command(["emu", "sms", "pdu", pdu.toString("hex")]);
  if (!/OK/.test(sent))
    throw Error("Emulator radio rejected the synthetic SMS PDU");
  test(
    "incomingOriginBoundOtpIsPersistedAndCopiedOffline",
    undefined,
    smsSuite,
  );
}
console.log(
  "PASS: committed encrypted receipt recovered in a new process, interrupted AtomicFile replacement retained, revision acknowledgement preserved, and synthetic MMS codec verified. Carrier transport and locked/Direct Boot behavior are separate gates.",
);
