# Acceptance evidence

Tests are PASS, FAIL or NOT TESTED. Code existing or an APK building is not device/carrier evidence. This experimental release is not approved for a daily primary phone until physical gates pass.

| Gate                                                                       | Status     | Evidence/limit                                                                                                        |
| -------------------------------------------------------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------- |
| Native Android debug/release compilation                                   | PASS       | Gradle debug/release APK + AAB, full lintRelease; SDK 37 / min 29                                                     |
| Local OTP rules                                                            | PASS       | English/Hindi/Bengali, spaced numeric/alphanumeric, negative/ambiguous fixtures                                       |
| Web/API/adapter compilation and TypeScript checks                          | PASS       | npm build/check                                                                                                       |
| Shared contracts, six SMS boxes and Unicode                                | PASS       | contracts unit tests                                                                                                  |
| Encryption/AAD and retained-version rotation                               | PASS       | server security unit tests                                                                                            |
| Production auth configuration guards                                       | PASS       | missing/wildcard allowlist, insecure origin, emulator/debug/CORS checks                                               |
| Real PostgreSQL migration and ingestion                                    | PASS       | Disposable local PostgreSQL 18, scoped migration                                                                      |
| Retry after committed response, identical source messages, older revisions | PASS       | Database integration assertions                                                                                       |
| Individual malformed records and suppression after purge                   | PASS       | Database integration assertions                                                                                       |
| Owner substitution and default sensitive retrieval policy                  | PASS       | Database integration assertions                                                                                       |
| Bounded search/incomplete scans                                            | PASS       | 600-record integration fixture; 50,000-record benchmark in performance.md                                             |
| Five SDK MCP tools and annotations                                         | PASS       | Linked SDK client/server transport, scoped search                                                                     |
| npm dependency audit                                                       | PASS       | Zero reported vulnerabilities at initial dependency review                                                            |
| Physical carrier SMS/MMS, dual SIM and OEM behavior                        | NOT TESTED | No physical phone connected at initial discovery                                                                      |
| Normal SMS role denial/acceptance and permission grants                    | PASS       | Android 16 emulator, normal system prompts; role loss/re-selection and other installers remain untested               |
| Offline incoming SMS, OTP notification and clipboard action                | PASS       | Android 16 emulator with Wi-Fi/data disabled; synthetic receipt persisted and exact code pasted into compose          |
| PIN-locked copy action, duplicate/stale notification edge cases            | NOT TESTED | Requires further device scenarios                                                                                     |
| Crash injection at journal/provider/outbox/acknowledgement boundaries      | NOT TESTED | Required release gate                                                                                                 |
| Reboot before first unlock and journal replay                              | NOT TESTED | Physical Direct Boot test required                                                                                    |
| Force-stop and explicit relaunch                                           | PASS       | Android 16 debug inbox retained three synthetic messages                                                              |
| Doze/battery saver and measured OEM delays                                 | NOT TESTED | Physical device required                                                                                              |
| Same-key debug upgrade and Room schema migration                           | PASS       | Repeated install -r retained encrypted journal/keys; Room 1 → 2 retained three messages                               |
| Release upgrade/login retention and account/server binding                 | NOT TESTED | Separate real-account/device scenarios required                                                                       |
| Android 17 restriction/exemption behavior                                  | NOT TESTED | Appropriate Android 17 image/device required                                                                          |
| Debug/release native Google login                                          | NOT TESTED | Requires actual account/device flows                                                                                  |
| Remote OAuth PKCE/resource/refresh/revocation                              | NOT TESTED | HTTP authorization and real-client verification required                                                              |
| Deployed web Google login                                                  | PASS       | Actual Google sign-in at HTTPS origin; Firebase owner matched database binding and UID pinned privately               |
| Browser session isolation on logout                                        | PASS       | PostgreSQL test revokes current cookie and preserves second cookie/phone identity; production sign-out retest pending |
| Source/history/release private-credential scans and license review         | PASS       | Gitleaks source/history; APK checked against private secrets; NOTICE reviewed                                         |
| Coolify origin health/shared database and push deploy                      | PASS       | Non-root healthy container; existing PostgreSQL runtime role; signed webhook                                          |
| Public hostname TLS and ready health                                       | PASS       | Proxied DNS; hostname-only Cloudflare Strict TLS rule active; public HTTPS /health/ready returned 200                 |
| RunnerMesh dedicated project/build-on-push                                 | NOT TESTED | GitHub installation awaiting account re-authentication                                                                |

Update rows only after the corresponding assertion or observed result. Do not broaden a unit result to untested physical behavior.

Initial experimental APK 0.1.0 was withdrawn after emulator testing revealed that a legacy default-SMS lookup disagreed with the granted Android role. Version 0.1.1 uses RoleManager and passed replay of retained encrypted receipts. Physical release gates remain open.
