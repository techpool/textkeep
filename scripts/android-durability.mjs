import { execFileSync, spawn } from "node:child_process";
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
const runtimeApi = command(["shell", "getprop", "ro.build.version.sdk"]).trim();
if (
  process.env.TEXTKEEP_EXPECTED_ANDROID_API &&
  runtimeApi !== process.env.TEXTKEEP_EXPECTED_ANDROID_API
)
  throw Error("Unexpected runtime Android API: " + runtimeApi);
console.log("Runtime Android API", runtimeApi);
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
async function radioTest() {
  const method = "incomingOriginBoundOtpIsPersistedAndCopiedOffline";
  await new Promise((resolve, reject) => {
    // Starting instrumentation force-stops its target. Start it before radio
    // delivery, or the harness can kill the SMS receiver mid-journal write.
    const child = spawn(adb, [
      "-s",
      serial,
      "shell",
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "class",
      "io.github.techpool.textkeep.DefaultSmsTest#" + method,
      target + ".test/androidx.test.runner.AndroidJUnitRunner",
    ]);
    let output = "",
      injected = false;
    const timeout = setTimeout(() => {
      child.kill();
      reject(Error("Radio test timed out"));
    }, 90000);
    child.stdout.on("data", (chunk) => {
      output += chunk;
      if (!injected && /INSTRUMENTATION_STATUS_CODE: 1/.test(output)) {
        injected = true;
        try {
          const sent = command([
            "emu",
            "sms",
            "send",
            "40404",
            "Your OTP is 515253.\\n@example.test #515253",
          ]);
          if (!/OK/.test(sent))
            throw Error("Emulator rejected the synthetic SMS");
        } catch (error) {
          clearTimeout(timeout);
          child.kill();
          reject(error);
        }
      }
    });
    child.stderr.on("data", (chunk) => {
      output += chunk;
    });
    child.on("error", (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timeout);
      if (
        code !== 0 ||
        !injected ||
        !/OK \(1 test\)/.test(output) ||
        /FAILURES!!!|INSTRUMENTATION_FAILED/.test(output)
      ) {
        console.error(output);
        reject(Error("Instrumentation failed: " + method));
      } else {
        console.log("PASS", method);
        resolve();
      }
    });
  });
}
test("prepareCommittedJournalForRestart", "prepare");
command(["shell", "am", "force-stop", target]);
test("verifyCommittedJournalAfterRestart", "verify");
test("acknowledgementCannotEraseAnEditMadeDuringUpload");
test("mmsTextPduPreservesUnicodeAndHeaders");
test("firebaseAuthKeepsApplicationContextAndSealsPreferences");
if (process.argv.includes("--sms")) {
  const smsSuite = "io.github.techpool.textkeep.DefaultSmsTest";
  test("selectThroughNormalSystemRolePrompt", undefined, smsSuite);
  command(["shell", "svc", "wifi", "disable"]);
  command(["shell", "svc", "data", "disable"]);
  await radioTest();
}
console.log(
  "PASS: committed encrypted receipt recovered in a new process, interrupted AtomicFile replacement retained, revision acknowledgement preserved, and synthetic MMS codec verified. Carrier transport and locked/Direct Boot behavior are separate gates.",
);
