# Self-hosting

Use a PostgreSQL instance you already operate. Inspect its container, private destination network, database and version before provisioning. Never use Coolify's control-plane database, expose the PostgreSQL port, reset the cluster or run destructive down migrations. Production Compose contains only the application.

1. Create a `textkeep` schema in the intended database, owned by a dedicated migration role. Grant a separate runtime role USAGE on that schema, SELECT/INSERT/UPDATE/DELETE on its tables and USAGE/SELECT on its sequences. Do not grant runtime schema creation or access to unrelated application tables.
2. Create your own Firebase project for authentication only. Register `io.github.techpool.textkeep` and `io.github.techpool.textkeep.debug` separately, plus a web app. Enable Google sign-in and add the actual signing certificate SHA-1 and SHA-256 for each Android variant. Add your HTTPS domain to authorized domains. Use the correct web/server OAuth client ID for Credential Manager. Do not enable hosting, analytics, Firestore, storage or paid products.
3. Provide Firebase Admin service credentials with only the required authentication privileges. Set `ALLOWED_GOOGLE_EMAILS` to explicitly authorized, verified Google emails; an empty or wildcard allowlist is invalid. `OWNER_FIREBASE_UID` can additionally pin a personal owner after verified bootstrap. Public Firebase configuration does not grant backend access.
4. Generate independent random 32-byte message and lookup keys. Keep versioned message keys in `MESSAGE_ENCRYPTION_KEYRING`, and set `ACTIVE_MESSAGE_KEY_ID`. Generate an RSA OAuth signing JWK and provide `OAUTH_JWKS`. Keep all private material outside Git and Docker build contexts.
5. Copy the example configuration into ignored private storage. Run `node apps/server/dist/index.js --migrate` once with the migration connection. The migration uses an advisory lock and affects only `textkeep`. Set the runtime connection after migration. Forward migrations preserve unrelated schemas and do not change existing backup schedules.
6. Build `deployment/Dockerfile` and deploy `deployment/compose.production.yaml`. Set `DATABASE_NETWORK` to the verified external private network and `PRIVATE_RUNTIME_DIRECTORY` to a protected host directory containing `firebase-admin.json`, optional `textkeep.apk`, and matching `release.json`. Give only the runtime container user read access. Configure HTTPS for the app's port 3000. Ensure proxy logs do not capture query strings or request bodies for sensitive routes.
7. Verify health/readiness, database connectivity from the app container, auth denials, Google login, OAuth discovery/consent and signed downloads. Run `node scripts/smoke.mjs https://your-host.example`.

## Android build inputs

Provide `TEXTKEEP_SERVER`, `FIREBASE_ANDROID_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID` and `GOOGLE_CLIENT_ID` as environment variables or ignored Gradle properties. Build each variant with its matching Firebase app ID. These client values are intentionally present in the configured APK; they are not database/admin credentials.

Release signing uses private `TEXTKEEP_KEYSTORE`, `TEXTKEEP_STORE_PASSWORD`, `TEXTKEEP_KEY_ALIAS` and `TEXTKEEP_KEY_PASSWORD`, or the trusted RunnerMesh signing injection. Retain one stable signing identity for upgrades. Never regenerate a key on every build. `RUNNERMESH_BUILD_NUMBER` and `RUNNERMESH_APP_VERSION` set version code/name.

Build `testDebugUnitTest assembleDebug assembleRelease bundleRelease`, verify the signed APK with `apksigner verify --print-certs`, and compute SHA-256. Supply `release.json` with `version`, `sha256`, `certificateSha256`, `size`, and `experimental: true` until physical-device gates pass. The download link is shown only when release metadata is configured.

## Coolify and push deployment

Create an application from your trusted Git repository and main branch with the Docker Compose build pack, path `/deployment/compose.production.yaml`, and HTTPS domain mapped to the app service on port 3000. Enable the existing Git integration's push trigger. Store secrets as Coolify environment values/private bind-mounted files. Do not copy service credentials into image layers.

Use RunnerMesh for trusted Android pushes: create a dedicated project, bind only this repository, configure the native Android directory/module, authorize its signing credential and Firebase environment group, and enable the exact main-branch rule. Do not run untrusted public pull requests on a personal persistent runner. The supplied YAML has no literal secrets and does not publish to Google Play.

There is no additional backup/restore system. Database or key loss can lose server copies. Preserve your existing infrastructure policies.

Coolify validates bind mounts before expanding variables. The production Compose file uses `/data/textkeep/private` as its conventional private runtime directory. Create that directory on the deployment host with access limited to the application UID (1000), or edit the bind path for your host before deployment.

Run the production Compose file with `docker compose --project-directory . -f deployment/compose.production.yaml ...` from the repository root. Coolify uses that project directory automatically. Mark only `DATABASE_NETWORK` as available during build so Compose can resolve its external network; keep all authentication and encryption secrets runtime-only. Application startup validates required runtime values.
