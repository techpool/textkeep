# Android behavior and troubleshooting

Local telephony does not depend on Google or cloud availability. Android presents the default SMS role prompt after a scope warning. Denial leaves the app without ownership of delivery. Role loss must stop provider writes and explain incomplete companion access, especially on Android 17 where OTP protections apply to non-exempt apps.

SMS permissions are hard restricted. Use the normal installer and role/runtime permission flows, inspect the actual granted state and do not assume sideloading grants an exemption. Do not root, bypass OS security with ADB or claim a fake role. No contacts, call logs, accessibility, overlays, notification listener or broad storage permission is required.

The SMS receiver joins multipart segments and preserves original Unicode and sender identifiers. Explicit pending intents track send/delivery outcomes per segment. Draft, outbox, failed and queued provider types remain distinct. SIM selection lists active subscriptions after an explained phone-state permission request. The system-default option is available without that permission. No call-log access is implemented. The MMS compatibility component can use the subscription number locally when grouping incoming MMS recipients. Verify dual-SIM behavior on a phone with the real SIM configuration.

Opening a conversation in default-handler mode marks its incoming provider rows as read/seen. Other threads and outgoing boxes retain their state. The provider observer reconciles that metadata change into the archive; companion access does not write read state.

OTP recognition is local and versioned. It requires code context, ranks nearby numeric/alphanumeric candidates and rejects ambiguity. Only fresh incoming receipts trigger code alerts. Copy uses a direct secure activity, lock authentication and sensitive clipboard metadata; it never automatically overwrites the clipboard or promises guaranteed erasure.

Settings offers 30-second, 1-minute, 2-minute and 5-minute notification display times. This controls notification dismissal, not the validity of the code. The Sync screen shows the saved server, registered device, Android version, role/permission state, last upload/reconciliation and the current provider-row scan checkpoint. Provider IDs are not message counts, so the checkpoint is not presented as a completion percentage. Withdrawing upload consent pauses sync, and status follows persisted updates without rescanning the inbox for every progress update.

Ordinary process death can be recovered through telephony delivery and the encrypted journal. Force-stop is different: reopen the app to reconcile accessible provider records. WorkManager's 15-minute periodic task is a safety net, not an exact schedule. Doze, battery saver, OEM restrictions, revoked permissions, disabled notifications and offline periods are surfaced as limitations.

Before first unlock, receipts use the public-key envelope journal and never access Firebase tokens or credential-protected Room state. After unlock, replay removes acknowledged journal files. Normal screen lock after first unlock is a different state. Physical-device tests must check OEM lock-screen surfaces and Direct Boot delivery rather than inferring success from code.

Android backup and device-transfer rules exclude local private state. The app does not change separate system SMS backup settings. Reinstallation can change device/provider identity and requires deliberate reconciliation; duplicate risk is documented rather than silently merging across devices.

MMS is part of local default-handler responsibilities. WAP notifications are persisted and downloaded with native carrier APIs; failures remain visible and retryable. Attachments remain local. Carrier receive/download/send/display tests are required before daily default-handler use.

Outgoing MMS retains the PDU's standard carrier-supplied sender token. It does not call the compatibility library's phone-number lookup helper or request phone-number access merely to compose a message.

Receipt identities use the telephony service’s `messageId` when supplied. Older/OEM deliveries without that identifier receive independent journal IDs, so genuinely identical messages are not merged by sender/body/timestamp or PDU content. Recovery of a retained journal is idempotent; duplicate broadcasts lacking a stable system ID can remain duplicated. Verify that behavior on the intended carrier/OEM.

On API 37, provider inserts also store the receipt identity in Android's documented opaque [SMS transaction-ID column](https://developer.android.com/reference/android/provider/Telephony.TextBasedSmsColumns#TRANSACTION_ID). Earlier providers use a durable pre-insert row high-water mark, the provider's read-only creator identity and the original message fields. A fresh receipt never adopts an older identical provider row. Recovery does not reject its own insert merely because reconciliation already indexed it. Legacy receipts without a pre-insert boundary and provider resets can remain ambiguous; preserving a receipt takes precedence over speculative merging.

Incoming deliveries replay their specific journal entry first. Deferred/invalid entries remain encrypted and move behind other recovery work, so a failed MMS does not block a fresh SMS or cause a historical OTP alert. Receivers process one selected receipt rather than scanning the recovery backlog; normal recovery remains bounded to 100 entries per pass.

## Pending uploads and retry

Pending is the number of local SMS source records whose newest revision has not been acknowledged by the server. It includes rejected records; it is not a count of failed sends, and can increase while initial import discovers more rows or messages change. The Sync screen shows the upload worker's running/waiting state, its scheduled retry time and any server-requested wait.

Successful bounded upload chunks queue a fresh continuation rather than increasing failure backoff. Real transport errors still retry with exponential backoff, Android network/background constraints still apply, and HTTP 429 waits are honored. **Reconcile and retry** clears record rejections and replaces an old delayed upload job without clearing the queue, account, keys or registration. While cloud sync is paused, the button only requests local reconciliation. Reopening the app and periodic reconciliation also request upload work; they preserve an existing upload job.

## Framework durability checks

Build `assembleDebug assembleDebugAndroidTest`, install both generated APKs normally on a disposable emulator, and run:

```sh
ANDROID_HOME=/path/to/android-sdk node scripts/android-durability.mjs emulator-5554
```

The runner refuses physical devices and targets only the `.debug` application. Tests also refuse an emulator with cloud synchronization enabled. Its staged journal check commits an encrypted synthetic receipt, leaves an unfinished AtomicFile replacement, force-stops the app, and verifies recovery in a different process. Separate checks reopen a real Room database after an acknowledgement races with a newer source revision, and round-trip a Unicode MMS retrieval PDU. It never sends carrier messages or uploads fixtures.

The staged outbox check also restarts the process after committing an encrypted pending upload, verifies that another account/device binding cannot select it, acknowledges it and reopens the database to verify the committed acknowledgement. With `--sms`, a provider-boundary fixture leaves two genuinely distinct receipts with identical sender/body/time and one already-committed provider row without its Room acknowledgement. After a process restart, replay must reuse that row, preserve both receipt identities, avoid extra copies on repeated replay, and suppress historical OTP alerts. These model committed boundary states; they do not assert exhaustive crash injection at every instruction.

These checks use Android's actual Keystore, files and SQLite through [AndroidJUnitRunner](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner). They do not establish real carrier MMS transport, Direct Boot before first unlock, or OEM background reliability. The staged methods are skipped in a generic connected-test run; use the script to exercise the required restart sequence.

Add `--sms` to select the debug app through its normal onboarding and Android role picker, turn off emulator Wi-Fi/mobile data, inject a synthetic origin-bound OTP through the emulator radio, and assert provider persistence, private/public notification content, and the unlocked notification copy action. No role, permission or app-op is granted through a shell override. Debug installations use the distinct name “TextKeep Debug” to prevent ambiguity with a release app in the system picker.

The normal role check first cancels Android's prompt and verifies that the role remains ungranted, then requests it again and explicitly selects the debug app. The provider-boundary fixture cleans up only its UUID-tagged synthetic rows on the disposable emulator.

The `--sms` sequence also runs active process-termination tests at nine persistence boundaries: encrypted journal commit, Room receipt storage, provider preparation/insertion, provider acknowledgement, journal removal, pending source storage, and before/after upload acknowledgement. Instrumentation arms one fixture-specific checkpoint, then enters the same journal/replay or source-commit function used by the application. The debug process terminates immediately at that checkpoint. The next instrumentation process must find its durable marker, have a different PID, and verify retained content, provider identity, pending/acknowledged revision and account binding. Receipt recovery must remain idempotent and must not alert for the historical fixture.

The fault implementation exists only in the debug source set and refuses physical hardware. The release source set contains a no-op checkpoint with no arming method, fault preferences or process-termination code. No receiver, activity, URL, setting or network endpoint can arm a fault. Source/acknowledgement tests exercise the actual local commit functions with a synthetic Room database; server commit/lost-response behavior is covered separately by HTTP/PostgreSQL integration tests. Carrier send callbacks, PIN lock and Direct Boot remain separate gates.

Version 0.1.4 also binds each HTTP upload to its selected queue before authentication. Changing account/server/device or pausing consent during token refresh rejects the request. A late acknowledgement cannot update a replacement row belonging to another binding, even if provider ID, epoch and revision match. Local capture uses persisted registration rather than the current Google login, so signed-out receipts remain queued for the registered archive. Source writes, registration and explicit reset use Room transactions. Unit tests intercept HTTP requests without network access; the framework runner exercises the corresponding Room boundary with synthetic data.
