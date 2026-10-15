# TextKeep

A native Android SMS manager with offline verification-code copy notifications, an encrypted self-hosted SMS archive, a React dashboard and separately authorized read-only MCP tools. **TextKeep is a provisional name.**

This is an experimental sideloaded application. Verify carrier SMS/MMS and your device's background/lock-screen behavior on a test phone before using it as your daily default messaging app. A built APK is not proof of carrier reliability. See [the acceptance checklist](docs/acceptance.md).

Hosted app: [textkeep.decafdevs.com](https://textkeep.decafdevs.com/). The [signed 0.2.0 APK](https://github.com/techpool/textkeep/releases/download/v0.2.0/textkeep-0.2.0.apk) uses this backend with a separate archive for each verified Google user. Connect an OAuth-capable Streamable HTTP client to `https://textkeep.decafdevs.com/mcp`; sensitive access is optional and separate.

APK version 0.2.0, build 29. SHA-256: `a862a038224bb67d231534bc2140d1b73a7993a661dadfdc0894de66bd2d5477`. Install as an update without uninstalling an existing release.

## What it does

- Organizes conversations with search, unread/code/archive filters, pins, unread badges and local archive/restore. Offers inline SMS replies and a full image/SIM/draft composer, with System, Light and Dark themes.
- Keeps cloud controls and live progress in Settings. Successful upload chunks continue without failure backoff, and normal lock/unlock does not start another reconciliation.
- Receives, persists and sends local SMS without needing cloud login. Handles multipart SMS, Unicode, short codes, original sender IDs, drafts and send/delivery callbacks.
- Recognizes OTP context locally and offers a user-initiated sensitive clipboard action. Historic imports do not generate fresh OTP alerts. The lock-screen public notification hides message content by default.
- Uses Android's default SMS role. Native SMS and carrier MMS transport are part of that responsibility. Cloud sync covers Android SMS provider records in inbox, sent, draft, outbox, failed and queued states. MMS attachments, RCS and other apps' private messages are outside cloud sync.
- Uses Google/Firebase for identity. After consent, full SMS including OTPs and banking messages uploads to the selected self-hosted server.
- Offers indexed metadata filtering, bounded decrypt-and-match search, device revocation and explicit archive deletion with suppression records.
- Exposes five read-only MCP tools through Streamable HTTP and a scoped stdio adapter. Sensitive messages require separate explicit owner consent.

Material 3 on Android and MUI on the web share a restrained green/cream visual system. All sample messages are synthetic. There is no analytics, cloud LLM, remote SMS sender, perpetual foreground sync service, second production database server or separate backup product.

| Light inbox | Dark inbox |
| --- | --- |
| ![Light Android inbox with synthetic test messages](docs/images/android-light-inbox.png) | ![Dark Android inbox with synthetic test messages](docs/images/android-dark-inbox.png) |

These Android screenshots come from the API 36 emulator tests. Search and filters cover loaded local messages; Load older messages expands that window. Pins and archives are local to the phone, and archived conversations stay archived when a new message arrives.

![Public homepage with a synthetic messaging preview](docs/images/landing-synthetic.png)

The homepage preview uses invented senders and messages. It is an illustration, not a capture of a user's inbox.

## Build

Pinned prerequisites: Node 24.14.1, Java 21 (Java 17 bytecode), Gradle wrapper 9.4.1, Android Gradle Plugin 9.2.1, Kotlin 2.3.21 and Android SDK 37. Android minimum API 29. Exact npm dependencies and Gradle library versions are committed.

```sh
npm ci
npm run build
npm run check
npm test
# Real database integration tests, using only a disposable local database:
docker compose -f deployment/compose.development.yaml up -d
TEST_DATABASE_URL=postgres://postgres:synthetic-test-only@127.0.0.1:55438/textkeep_test npm test
apps/android/gradlew -p apps/android testDebugUnitTest assembleDebug
```

Configure your own Firebase project and build inputs as described in [self-hosting](docs/self-hosting.md). A generic development build leaves cloud configuration blank and still supports local messaging. Release builds require your privately retained signing identity. Never commit `.env`, service-account credentials, deployment identifiers, real SMS fixtures or signing material.

## Run and connect

Copy `.env.example` into an ignored private environment file, replace all placeholders and run the explicit migration using separate migration credentials. Serve the production Compose application on HTTPS, attached to your existing database's private Docker network. [Docker/Coolify setup](docs/self-hosting.md) explains the required secrets and permissions.

The dashboard is `/app`; public pages are `/`, `/privacy` and `/self-host`. API contracts are `/api/docs`; health endpoints are `/health/live` and `/health/ready`. The release download route is enabled only when a matching signed APK and release metadata are supplied privately.

Remote MCP clients connect to `https://your-host.example/mcp`, discover the authorization server and use authorization-code flow with PKCE. [MCP configuration](docs/mcp.md) covers scopes, consent, revocation and the stdio adapter.

## Privacy and limits

Bodies and senders are encrypted with authenticated application-layer encryption, not end-to-end encryption. The backend decrypts authorized results; timestamps, message type and device metadata remain queryable. Losing database records or encryption keys can lose the archive. Existing infrastructure backup policies are outside this product.

Phone deletion leaves the server copy intact. Explicit server deletion suppresses ordinary re-upload for that device/provider identity, with limits across reinstall and provider resets. Account/server changes must pause sync, disclose queue binding and obtain renewed consent.

Process death differs from force-stop. Android/OEM restrictions, permission loss, notifications being disabled, Doze, offline periods and pre-unlock storage can delay processing. Reopen the app to reconcile accessible messages. [Android behavior and diagnostics](docs/android.md) details these limits.

SMS is untrusted text. The UI does not execute it or load URL previews. MCP clients can disclose returned data to their providers, and classifier/filtering rules cannot guarantee every sensitive value is excluded.

## License

Apache-2.0. Dependency attribution and the MMS compatibility component's maintenance status are documented in [NOTICE](NOTICE) and [architecture](docs/architecture.md). See [SECURITY.md](SECURITY.md) and [CONTRIBUTING.md](CONTRIBUTING.md).
