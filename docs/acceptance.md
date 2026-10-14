# Acceptance evidence

Tests are PASS, FAIL or NOT TESTED. Code existing or an APK building is not device/carrier evidence. This experimental release is not approved for a daily primary phone until physical gates pass.

| Gate                                                                              | Status     | Evidence/limit                                                                  |
| --------------------------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------- |
| Native Android debug compilation                                                  | PASS       | Gradle assembleDebug, SDK 37 / min 29                                           |
| Local OTP rules                                                                   | PASS       | English/Hindi/Bengali, spaced numeric/alphanumeric, negative/ambiguous fixtures |
| Web/API/adapter compilation and TypeScript checks                                 | PASS       | npm build/check                                                                 |
| Shared contracts, six SMS boxes and Unicode                                       | PASS       | contracts unit tests                                                            |
| Encryption/AAD and retained-version rotation                                      | PASS       | server security unit tests                                                      |
| Production auth configuration guards                                              | PASS       | missing/wildcard allowlist, insecure origin, emulator/debug/CORS checks         |
| Real PostgreSQL migration and ingestion                                           | PASS       | Disposable local PostgreSQL 18, scoped migration                                |
| Retry after committed response, identical source messages, older revisions        | PASS       | Database integration assertions                                                 |
| Individual malformed records and suppression after purge                          | PASS       | Database integration assertions                                                 |
| Owner substitution and default sensitive retrieval policy                         | PASS       | Database integration assertions                                                 |
| Bounded search/incomplete scans                                                   | PASS       | 600-record synthetic fixture and 500-row budget                                 |
| Five SDK MCP tools and annotations                                                | PASS       | Linked SDK client/server transport, scoped search                               |
| npm dependency audit                                                              | PASS       | Zero reported vulnerabilities at initial dependency review                      |
| Physical carrier SMS/MMS, dual SIM and OEM behavior                               | NOT TESTED | No physical phone connected at initial discovery                                |
| Role acceptance/denial/loss/re-selection and permission installer state           | NOT TESTED | Requires install/role-flow and device evidence                                  |
| Offline copy, locked action, stale imports and notification duplication on device | NOT TESTED | Requires device/emulator scenarios                                              |
| Crash injection at journal/provider/outbox/acknowledgement boundaries             | NOT TESTED | Required release gate                                                           |
| Reboot before first unlock and journal replay                                     | NOT TESTED | Physical Direct Boot test required                                              |
| Force-stop/relaunch, Doze/battery saver and measured OEM delays                   | NOT TESTED | Physical device required                                                        |
| Same-key upgrade retention and account/server binding                             | NOT TESTED | Install/upgrade scenarios required                                              |
| Android 17 restriction/exemption behavior                                         | NOT TESTED | Appropriate Android 17 image/device required                                    |
| Debug/release native Google login                                                 | NOT TESTED | Requires actual account/device flows                                            |
| Remote OAuth PKCE/resource/refresh/revocation                                     | NOT TESTED | HTTP authorization and real-client verification required                        |
| Deployed web Google login and CSRF/cookies                                        | NOT TESTED | Deployed-origin browser verification required                                   |
| Source/history/release secret scans and license review                            | NOT TESTED | Must complete before public release                                             |
| Coolify health/shared database/DNS/TLS and push build/deploy                      | NOT TESTED | Deployment evidence pending                                                     |

Update rows only after the corresponding assertion or observed result. Do not broaden a unit result to untested physical behavior.
