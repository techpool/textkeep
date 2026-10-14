# Android behavior and troubleshooting

Local telephony does not depend on Google or cloud availability. Android presents the default SMS role prompt after a scope warning. Denial leaves the app without ownership of delivery. Role loss must stop provider writes and explain incomplete companion access, especially on Android 17 where OTP protections apply to non-exempt apps.

SMS permissions are hard restricted. Use the normal installer and role/runtime permission flows, inspect the actual granted state and do not assume sideloading grants an exemption. Do not root, bypass OS security with ADB or claim a fake role. No contacts, call logs, accessibility, overlays, notification listener or broad storage permission is required.

The SMS receiver joins multipart segments and preserves original Unicode and sender identifiers. Explicit pending intents track send/delivery outcomes per segment. Draft, outbox, failed and queued provider types remain distinct. SIM selection lists active subscriptions after an explained phone-state permission request. The system-default option is available without that permission. No call log or phone number reading is implemented. Verify dual-SIM behavior on a phone with the real SIM configuration.

OTP recognition is local and versioned. It requires code context, ranks nearby numeric/alphanumeric candidates and rejects ambiguity. Only fresh incoming receipts trigger code alerts. Copy uses a direct secure activity, lock authentication and sensitive clipboard metadata; it never automatically overwrites the clipboard or promises guaranteed erasure.

Ordinary process death can be recovered through telephony delivery and the encrypted journal. Force-stop is different: reopen the app to reconcile accessible provider records. WorkManager's 15-minute periodic task is a safety net, not an exact schedule. Doze, battery saver, OEM restrictions, revoked permissions, disabled notifications and offline periods are surfaced as limitations.

Before first unlock, receipts use the public-key envelope journal and never access Firebase tokens or credential-protected Room state. After unlock, replay removes acknowledged journal files. Normal screen lock after first unlock is a different state. Physical-device tests must check OEM lock-screen surfaces and Direct Boot delivery rather than inferring success from code.

Android backup and device-transfer rules exclude local private state. The app does not change separate system SMS backup settings. Reinstallation can change device/provider identity and requires deliberate reconciliation; duplicate risk is documented rather than silently merging across devices.

MMS is part of local default-handler responsibilities. WAP notifications are persisted and downloaded with native carrier APIs; failures remain visible and retryable. Attachments remain local. Carrier receive/download/send/display tests are required before daily default-handler use.
