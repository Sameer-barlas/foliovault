# FolioVault Build Progress & Verification Log

Status legend: `[PASS]` verified by executing the check in this environment · `[FAIL]` failed check · `[UNVERIFIED]` could not execute here (exact command provided) · `[PENDING]` not yet started.

---

## Chunk 1 — Foundation, Design System, Data Model & Firestore Rules
Status: **COMPLETE**

- [x] `[PASS]` `plan.md` & `progress.md` created
- [x] `[PASS]` Warm Parchment Editorial theme (`#FAF6EE`, `#F3ECE0`, `#1C1917`, `#7C2D2D`, `#8B6B3E`, `#5C544B`, `#DDD4C4`, `#2E5E4E`, `#A62626`, `#141210`), local bundled fonts (`Playfair Display`, `Plus Jakarta Sans`, `JetBrains Mono`), custom adaptive launcher icon + 5 density PNGs
- [x] `[PASS]` Security hardening (`FLAG_SECURE` before `setContent`, `allowBackup="false"`, `dataExtractionRules`, cleartext off, `MemoryCacheSettings`, App Check monitor mode)
- [x] `[PASS]` Kotlin models, `PageImageStore`, `OrderIndexMath`, MVC skeleton under `/android-app`
- [x] `[PASS]` `/firebase/firestore.rules`, `firestore.indexes.json`, `firebase.json`, `rules-tests/firestore.test.js`
- [x] `[PASS]` **Checkpoint 1.1**: Rules tests — 9/9 passed (`FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firebase/rules-tests/firestore.test.js`)
- [x] `[PASS]` **Checkpoint 1.2**: Static checks — 0 matches for `signInAnonymously|signInWithEmailAndPassword` and 0 matches for `firebase-storage` in Gradle
- [x] `[PASS]` **Checkpoint 1.3**: Android app compiled cleanly (`compile_applet` BUILD SUCCEEDED)

---

## Chunk 2 — Express Backend & Auth Gate
Status: **COMPLETE**

- [x] `[PASS]` Express MVC backend (`/backend`: routes, controllers, services, middleware, Vercel `api/index.js` + `vercel.json`, Render `server.js`, `.env.example`, unit tests)
- [x] `[PASS]` Android `DeviceBindingService`, `DeviceApiService`, `AuthController`, `LoginScreen`, `NotAuthorizedScreen`, `DeviceLockedScreen`, live revocation guard, role router
- [x] `[PASS]` **Checkpoint 2.1**: Backend unit tests — 6/6 passed (`npm --prefix backend test`) covering invalid token, wrong audience, unlisted Gmail, first bind, same device, different device (`409 DEVICE_LOCKED`), super-admin bootstrap, non-admin reset rejected, super-admin reset protection, per-target (3/24h) and per-admin (20/hr) rate limits, and audit log written
- [x] `[PASS]` **Checkpoint 2.2**: Custom token carries strictly `deviceId`, `userEmail`, `role`; no secrets committed (`backend/.env` absent, `backend/.env.example` present)
- [x] `[PASS]` **Checkpoint 2.3**: Android auth gate compiled cleanly (`compile_applet` BUILD SUCCEEDED)

---

## Chunk 3 — Reader: Bookshelf & Paginated Image Reader
Status: **COMPLETE**

- [x] `[PASS]` `BookService` (access-scoped lookup, 12-page cursor pagination), `FirestoreBytesPageImageStore`, `ImageCodecService` + RAM `LruCache` (`PageMemoryCache`)
- [x] `[PASS]` `LibraryScreen` (2-column/3-column grid, search, pull-to-refresh) & `BookReaderScreen` (vertical scroll, prefetch at 3 from end, pinch/double-tap zoom, shimmer, floating `XX / YY` page pill, translucent Gmail watermark)
- [x] `[PASS]` **Checkpoint 3.1**: Pagination boundary (empty, exact 12, 27 -> 12+12+3), prefetch at 3 from end, zero duplicate reads on scroll back, `FLAG_SECURE`, and RAM cache clear tests passed (`gradle :app:testDebugUnitTest --tests com.example.FolioVaultUnitTests --tests com.example.FolioVaultSecurityRobolectricTest`)
- [x] `[PASS]` **Checkpoint 3.2**: Memory-only `MemoryCacheSettings`, `FLAG_SECURE`, zero disk writes / zero download URLs verified by static grep + Robolectric
- [x] `[PASS]` **Checkpoint 3.3**: Android app compiled cleanly (`compile_applet` BUILD SUCCEEDED)

---

## Chunk 4 — Admin Portal
Status: **COMPLETE**

- [x] `[PASS]` Books CRUD, cover upload (<= 60 KB WebP), paginated 12-page filmstrip editor
- [x] `[PASS]` Zero-permission Photo Picker -> on-device WebP compression (<= 150 KB target, <= 300 KB hard max) -> positional insert (start, end, before/after any page) & delete + fractional `orderIndex` rebalance (`< 0.001`) + `pageCount`/`app_stats/storage` sync
- [x] `[PASS]` Users & Access Manager, server-side Reset Device dialog (`POST /api/reset-device`), Audit tab (`reset_audit_logs`), Spark Quota card (1 GiB + 80% warning), Reader Preview toggle, debug-only Canvas Seed Loader (3 books × 15 pages)
- [x] `[PASS]` **Checkpoint 4.1**: `OrderIndexMath` start/middle/end/rebalance unit tests passed (`gradle :app:testDebugUnitTest`)
- [x] `[PASS]` **Checkpoint 4.2**: Removing a user cascades deletion of `access` docs (`removeUserAndAccess`), zero email fields written to `books`, resets strictly through backend API, zero storage permissions in manifest, Seed Loader strictly gated by `BuildConfig.DEBUG && isAdmin`
- [x] `[PASS]` **Checkpoint 4.3**: Android app compiled cleanly (`compile_applet` BUILD SUCCEEDED)

---

## Chunk 5 — Website, Release Build, README & Final Audit
Status: **COMPLETE**

- [x] `[PASS]` Static Warm Parchment website (`/website`: `index.html`, `styles.css`, `config.js` with single-place `APK_URL`)
- [x] `[PASS]` Release ProGuard/R8 rules (`/app/proguard-rules.pro`), zero token logging, AI Studio `signingConfigs` preserved intact, env-var release signing documented
- [x] `[PASS]` Step-by-step `README.md` (Firebase Spark setup, Auth/Firestore/rules deploy, debug & release SHA-1, Web Client ID, Vercel/Render deploy, `API_BASE_URL`, keystore generation + backup warning, GitHub Releases APK publishing, website hosting, lost super-admin phone recovery, outgrowing Spark guide, known constraints)
- [x] `[PASS]` **Checkpoint 5.1**: Code-to-rules parity audit — every Firestore path in Android (`authorized_users`, `access`, `books`, `books/{bookId}/pages`, `reset_audit_logs`, `app_stats/storage`) and Backend (`authorized_users`, `reset_audit_logs`, `rate_limits`) matches `/firebase/firestore.rules` 1:1
- [x] `[PASS]` **Checkpoint 5.2**: Full re-run passed — Firestore rules tests (9/9 PASS), Express backend tests (6/6 PASS), Android JVM + Robolectric unit tests (BUILD SUCCESSFUL), final `compile_applet` (BUILD SUCCEEDED)

---

## UNVERIFIED Items (Require Local Credentials / Production Keystore / Live Firebase Project)

1. **Signed Release APK Build (`[UNVERIFIED]`)** — No production upload keystore (`my-upload-key.jks`) or release signing passwords exist in this cloud container. Run locally after generating your upload keystore:
   ```bash
   keytool -genkeypair -v -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   KEYSTORE_PATH="$(pwd)/my-upload-key.jks" \
   STORE_PASSWORD="<store_pass>" \
   KEY_PASSWORD="<key_pass>" \
   API_BASE_URL="https://<your-backend-host>" \
   GOOGLE_WEB_CLIENT_ID="<your-web-client-id>.apps.googleusercontent.com" \
   gradle :app:assembleRelease
   ```
2. **Live Firebase Console Deployment & Real Device Google Sign-In / Play Integrity (`[UNVERIFIED]`)** — Manual Firebase Spark setup was selected (no live Firebase project credentials or physical Android device in this environment). Run locally after creating your Spark project and adding `app/google-services.json`:
   ```bash
   cd firebase && firebase login && firebase use --add && firebase deploy --only firestore:rules,firestore:indexes
   ```
