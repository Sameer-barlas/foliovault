# FolioVault — Master Build Plan (Firebase Spark Edition)

## Architecture & Confirmed Decisions

- **Android App (`/android-app` + root Gradle host `/app`)**: Native Kotlin + Jetpack Compose (Material 3, Warm Parchment Editorial theme, local Playfair Display / Plus Jakarta Sans / JetBrains Mono fonts). All FolioVault MVC source layers (`models/`, `services/`, `controllers/`, `views/`, `routes/`) and unit tests live inside `/android-app`, connected directly via Gradle `sourceSets` to root `:app` so AI Studio builds and streaming preview work with zero code duplication.
- **Express Backend (`/backend`)**: Node.js MVC (`src/routes`, `src/controllers`, `src/services`, `src/middleware`, `src/config`) with Vercel serverless (`api/index.js`, `vercel.json`) and Render/local (`server.js`) entry points using Firebase Admin SDK.
- **Firebase (`/firebase`)**: `firestore.rules`, `firestore.indexes.json`, `firebase.json`, and `rules-tests/firestore.test.js`. Manual Firebase Spark setup (no auto-provisioning). Single `FIRESTORE_DATABASE_ID` constant defaulting to `(default)`.
- **Website (`/website`)**: Static Warm Parchment landing page (`index.html`, `styles.css`, `config.js`) pointing to a GitHub Releases APK asset.
- **Release Signing**: AI Studio's Gradle `signingConfigs` (`release` using `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` env vars and `debugConfig`) are preserved intact; release signing and `keytool` steps are documented in `README.md`.

## Locked Security & Data Model Invariants

1. **Custom Token Claims**: Strictly `deviceId`, `userEmail`, `role` (never `email`/`email_verified`).
2. **Firestore Collections**:
   - `authorized_users/{email}` (lowercase Gmail doc ID)
   - `access/{email__bookId}` (zero emails stored in `books`)
   - `books/{bookId}` (`coverBytes` <= 60 KB, `pageCount`)
   - `books/{bookId}/pages/{pageId}` (`orderIndex: double`, `imageBytes` WebP <= 300 KB, target <= 150 KB, ~1080 px wide)
   - `reset_audit_logs/{logId}` (admin read-only, server write-only)
   - `rate_limits/{key}` (server only — 3 resets/target/24h, 20 resets/admin/hr)
   - `app_stats/storage` (`totalImageBytes`, `updatedAt`, admin read/write)
3. **Super-Admin**: `muhamad517uet@gmail.com` (bootstrapped on first login, protected from delete/demote).
4. **Content Protection**: `FLAG_SECURE` before `setContent`, translucent Gmail watermark, `allowBackup="false"`, `dataExtractionRules` excluding all domains, cleartext traffic disabled, Firestore `MemoryCacheSettings`, RAM-only bitmap `LruCache` cleared on exit/sign-out/revocation, zero storage permissions (Photo Picker only), no Firebase Storage dependency, no `signInAnonymously` or email/password auth.
5. **Storage Abstraction**: `PageImageStore` interface (`FirestoreBytesPageImageStore`) for future Blaze/Storage swap.
6. **Debug Seed Loader**: Strictly gated behind `BuildConfig.DEBUG && isAdmin`.

## 5 Execution Chunks

- **Chunk 1**: Foundation, Design System, Data Model, Security Hardening, Firestore Rules & Emulator Tests.
- **Chunk 2**: Express Backend (`health`, `bind-device`, `reset-device`, rate limiter, unit tests) & Android Auth Gate + Live Revocation Guard.
- **Chunk 3**: Reader Bookshelf, `BookService` (12-page cursor pagination), `FirestoreBytesPageImageStore`, `ImageCodecService` + RAM `LruCache`, and Paginated Zoomable Watermarked Reader.
- **Chunk 4**: Admin Portal (Books CRUD, Photo Picker WebP compression, 12-page filmstrip, positional insert/delete + fractional `orderIndex` rebalance, Users & Access Manager, server-side Reset Device, Audit sheet, Spark Quota card, Reader Preview, Debug Seed Loader).
- **Chunk 5**: Static Website, Release ProGuard/R8 & Signing Guide, Step-by-Step `README.md`, Code-to-Rules Parity Audit, and Final Checkpoint Verification.
