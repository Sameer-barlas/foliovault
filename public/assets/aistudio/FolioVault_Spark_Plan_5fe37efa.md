# FolioVault — Secure Single-Device Book Reader (Firebase Spark Edition)

FolioVault is an Android app where authorized Gmail readers read image-only books, locked to one physical phone per Gmail. Admins manage users, books, pages and device resets from the same app. Everything runs on the **Firebase Spark (free) plan** plus a small **Express backend** hosted free on Vercel (or Render). **No Cloud Functions, no Firebase Storage, no Blaze plan.**

---

## 0. Locked Decisions (do not change without asking the owner)

1. **Stack**: Native Android (Kotlin, Jetpack Compose, MVC-style layers) + Firebase Auth + Firestore + a Node.js/Express backend (MVC: routes / controllers / services) using the Firebase Admin SDK.
2. **Device binding is enforced on the server.** The backend verifies the Google ID token, binds the device, and mints a Firebase **custom token** with claims `deviceId`, `userEmail`, `role`. Firestore rules require `request.auth.token.deviceId == boundDeviceId`. (Claim names are exactly these three; do not use `email` / `email_verified` claims.)
3. **Page images are stored in Firestore** as WebP `Bytes` fields (target ≤ 150 KB, hard max 300 KB, ~1080 px wide). No Storage, no public URLs, no download links.
4. **Zero email leakage**: book documents never contain subscriber emails. Grants live in `access/{email__bookId}`.
5. **No disk caching of content**: Firestore uses `MemoryCacheSettings`; decoded bitmaps live only in a RAM `LruCache`, cleared on exit / sign-out / revocation.
6. **`FLAG_SECURE` on the whole window** (set before `setContent`), plus a translucent Gmail watermark on reader pages. `allowBackup="false"` and `dataExtractionRules` excluding everything.
7. **Admin resets are server-side, rate-limited and audited.** Reader accounts cannot write `authorized_users` at all.
8. **Super-admin**: `muhamad517uet@gmail.com`. Bootstrapped by the backend on first login. The super-admin doc cannot be deleted or demoted by any client.
9. **Seed loader** exists only in debug builds (`BuildConfig.DEBUG && isAdmin`).
10. **Storage abstraction**: all page/cover image access goes through a `PageImageStore` interface (`FirestoreBytesPageImageStore` now). Moving to Firebase Storage later (Blaze) means replacing one class.
11. **Firestore database**: use the `(default)` database (the free quota is documented for it). If the AI Studio tooling forces a named database, keep its ID in a single constant `FIRESTORE_DATABASE_ID` used by both the Android app and the backend.
12. **Visual identity**: Warm Parchment Editorial (palette in section 3).

---

## 1. Overview

- **Readers**: sign in with Google (no passwords) → the device is bound on first sign-in → see only assigned books → read in vertical scroll, 12 pages per batch, pinch/double-tap zoom.
- **Admins**: same login, plus Books, Users and Audit tabs and a Reader Preview mode. Admin can add/remove user Gmails, assign/revoke books, add pages (from the Android Photo Picker) at any position, delete pages, and reset a user's device binding.
- **Website**: a small static intro page with a download button for the APK.
- **Audience**: authors, coaching institutes, and paid-material publishers distributing private image books.

---

## 2. User Flows

### 2.1 Login and device gate
1. Welcome screen with one **Continue with Google** button (Credential Manager, Web client ID as `serverClientId`).
2. App sends `{ idToken, deviceId, deviceLabel, appCheckToken }` to `POST /api/bind-device`.
3. Backend outcomes:
   - **Gmail not in `authorized_users`** → `403 NOT_AUTHORIZED` → *Access Not Granted* screen (shows the Gmail and "contact your administrator").
   - **No device bound yet** → bind atomically → return custom token.
   - **Same device** → return a fresh custom token.
   - **Different device** → `409 DEVICE_LOCKED` with only the bound device label (e.g. "Linked to Pixel 8") → *Device Locked* screen.
4. App calls `signInWithCustomToken`, then routes to the Library (reader) or Admin Hub (admin).
5. **Live Revocation Guard**: a snapshot listener on the user's own `authorized_users/{email}` doc. If the doc is deleted, `boundDeviceId` changes or becomes null, or the listener gets `PERMISSION_DENIED`, the app clears the RAM cache, signs out, and shows the Device Locked / Not Authorized screen.

### 2.2 Reader
- **Bookshelf**: query `access` where `email == own email`, then `get` each `books/{bookId}`. 2-column cover grid, pull-to-refresh, search.
- **Reader**: `pages` ordered by `orderIndex`, `limit(12)`, then `startAfter(last)`; prefetch the next batch when within 3 pages of the end. Pages are decoded on a background dispatcher into the RAM `LruCache`. Floating `04 / 18` page pill, shimmer placeholders while loading.

### 2.3 Admin
- **Books tab**: create/edit/delete books, cover upload (thumbnail ≤ 60 KB), filmstrip of pages (loaded in 12-page batches), **insert page before/after any page or at start/end**, delete page with confirmation.
- **Users tab**: add/remove Gmail (role `reader`), toggle book access (writes/deletes `access` docs), view bound device label and time, **Reset Device** (calls the backend; optional reason note; shows rate-limit errors).
- **Audit sheet**: latest `reset_audit_logs`.
- **Quota card**: approximate stored image bytes (from `app_stats/storage`) vs the 1 GiB free limit, with a warning at 80%.
- **Debug-only Seed Loader**: creates 3 books with 15+ pages each. Page images are generated on-device with Canvas (parchment page with title text and a simple illustration), compressed to WebP.

### 2.4 Website and release
Static site in `website/` with product intro, security pillars, install instructions ("Allow installs from this source"), and a **Download APK** button. The APK URL is set in one place (`website/config.js`) and points to a GitHub Releases asset.

---

## 3. Visual Identity

- **Canvas** `#FAF6EE`, **Card** `#F3ECE0` / `#EFE7D8`, **Ink** `#1C1917`, **Primary (oxblood)** `#7C2D2D` on `#FFFBF5`, **Secondary (brass)** `#8B6B3E`, **Muted text** `#5C544B`, **Divider** `#DDD4C4`, **Success** `#2E5E4E`, **Alert** `#A62626`, **Reader surround** `#141210`.
- **Fonts** (bundled locally): Playfair Display (titles), Plus Jakarta Sans (UI), JetBrains Mono (device IDs, counters).
- Edge-to-edge Material 3, adaptive layout (bottom bar on phones, rail on tablets), 48 dp touch targets, spring motion under 300 ms, snackbars for errors and rate limits.

---

## 4. Architecture and Repo Layout

```
Android app (FLAG_SECURE, App Check)
  views (Compose)  ->  controllers (ViewModels)  ->  services  ->  models
        |                                              |
        | HTTPS (idToken / Firebase ID token)          | Firebase SDK (custom-token session)
        v                                              v
Express backend (Vercel or Render)  --Admin SDK-->  Firestore (Spark)
  routes -> controllers -> services
```

```
/android-app     Kotlin app: models/ services/ controllers/ views/ routes/
/backend         Express MVC: src/routes, src/controllers, src/services, src/middleware, src/config
                 api/index.js (Vercel entry), server.js (Render/local entry), vercel.json, tests/
/firebase        firestore.rules, firestore.indexes.json, firebase.json, rules-tests/
/website         index.html, styles.css, config.js
plan.md  progress.md  README.md
```

One backend codebase deploys to **Vercel** (serverless via `api/index.js`) or **Render** (`node server.js`).

---

## 5. Data Model and Firestore Rules

Helpers (in `firestore.rules`):
- `userEmail()` = `request.auth.token.userEmail`
- `hasMatchingDevice()` = signed in AND `request.auth.token.deviceId is string` AND `get(authorized_users/userEmail()).data.boundDeviceId == request.auth.token.deviceId`
- `isAdmin()` = `hasMatchingDevice()` AND that same user doc has `role == 'admin'`
- `hasGrant(bookId)` = `exists(access/(userEmail() + '__' + bookId))`

### `authorized_users/{email}` (doc ID = lowercase Gmail)
`email, displayName, role ('admin'|'reader'), boundDeviceId?, boundDeviceLabel?, boundUid?, boundAt?, sessionVersion, createdAt, updatedAt`
- **get**: own doc (no device check, so the revocation listener can see changes) or `isAdmin()`.
- **list**: `isAdmin()` only.
- **create** (admin): `role == 'reader'`, `boundDeviceId == null`, only allowed keys.
- **update** (admin): only `displayName`, `updatedAt` may change. Bind/reset fields are server-only.
- **delete** (admin): not the super-admin doc.
- Readers: no writes. Ever.

### `access/{email__bookId}`
`email, bookId, grantedBy, grantedAt`
- **get/list**: `isAdmin()` OR (`hasMatchingDevice()` AND `resource.data.email == userEmail()`).
- **create/update/delete**: `isAdmin()`, valid schema, parent book exists.

### `books/{bookId}` (no subscriber emails)
`title, author, description, category, coverBytes (≤ 60 KB), pageCount, createdBy, createdAt, updatedAt`
- **get**: `isAdmin()` OR (`hasMatchingDevice()` AND `hasGrant(bookId)`).
- **list**: `isAdmin()` only.
- **write**: `isAdmin()`, `isValidBook()` (cover size check via `coverBytes.size()`).

### `books/{bookId}/pages/{pageId}`
`orderIndex (double), imageBytes (WebP, ≤ 300 KB), width, height, caption, createdAt, updatedAt`
- **get/list**: `isAdmin()` OR (`hasMatchingDevice()` AND `hasGrant(bookId)`).
- **write**: `isAdmin()`, parent book exists, `imageBytes.size() <= 300 * 1024`.

### `reset_audit_logs/{logId}`
`targetEmail, previousDeviceId, previousDeviceLabel, adminEmail, adminUid, reason, resetAt`
- **read**: `isAdmin()`. **write**: denied for all clients (backend Admin SDK only).

### `rate_limits/{key}` and `app_stats/storage`
- `rate_limits`: all client access denied (backend only).
- `app_stats/storage` (`totalImageBytes`, `updatedAt`): read/write `isAdmin()`.

### Ordering
`orderIndex` starts at 1000, 2000, 3000. Insert between A and B = `(A + B) / 2`. If the gap drops below 0.001, rebalance the book's pages in one batch (update `orderIndex` only).

All needed indexes are single-field (automatic). `firestore.indexes.json` stays empty unless a composite index becomes necessary.

---

## 6. Backend API (Express)

Environment variables: `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`, `FIRESTORE_DATABASE_ID` (optional), `GOOGLE_WEB_CLIENT_ID`, `SUPER_ADMIN_EMAIL`, `APPCHECK_ENFORCE` (default `false`). Never commit secrets.

- **`GET /api/health`** → `{ ok: true }`.
- **`POST /api/bind-device`** body `{ idToken, deviceId, deviceLabel }`, header `X-Firebase-AppCheck`:
  1. Verify Google ID token (`google-auth-library`, audience = `GOOGLE_WEB_CLIENT_ID`, `email_verified` true), lowercase the email.
  2. Verify the App Check token; log only unless `APPCHECK_ENFORCE=true`.
  3. Load `authorized_users/{email}`. Missing: create it with role `admin` if the email equals `SUPER_ADMIN_EMAIL`, otherwise `403 NOT_AUTHORIZED`.
  4. Firestore transaction: if `boundDeviceId` is null, bind it; if equal, continue; otherwise `409 DEVICE_LOCKED` with the bound label.
  5. `createCustomToken(uid = "g_" + googleSub, { deviceId, userEmail, role })` → `{ customToken, role }`.
- **`POST /api/reset-device`** body `{ targetEmail, reason }`, header `Authorization: Bearer <Firebase ID token>`:
  1. Verify the token. Re-read the caller's doc; require `role == 'admin'` and `deviceId == boundDeviceId`.
  2. Only the super-admin may reset the super-admin.
  3. Rate limits via `rate_limits`: max **3 resets per target per 24 h**, max **20 resets per admin per hour** (`429 RATE_LIMITED` with a retry time).
  4. Transaction: set `boundDeviceId/Label/Uid/At` to null, `sessionVersion + 1`, and write `reset_audit_logs`.

Result: after a reset or user deletion, the old phone's token stops working immediately, because the rules compare the token claim with the current `boundDeviceId`.

**Lost super-admin phone**: clear `boundDeviceId` on the super-admin doc manually in the Firebase Console (documented in README).

---

## 7. Spark Quota Budget (approximate; verify in the Firebase console Usage tab)

| Limit | Free quota | Practical meaning |
|---|---|---|
| Stored data | 1 GiB | ~6,500 pages at 150 KB |
| Reads | 50,000 / day | ~1 read per page view plus a few rule lookups per query |
| Writes | 20,000 / day | plenty for admin uploads |
| Outbound data | 10 GiB / month | ~70,000 page views at 150 KB |

Required mitigations: compress aggressively (target 150 KB, reject above 300 KB), RAM cache while reading so scrolling back does not re-read, 12-page batches, admin quota card, README section "What to do when you outgrow Spark" (switch to Blaze and swap `PageImageStore`).

---

## 8. Execution Chunks and Checkpoints

Each chunk is completed fully before the next. Each checkpoint updates `progress.md` with PASS / FAIL / UNVERIFIED per item. If a check cannot run in the current environment, mark it **UNVERIFIED** and write the exact command to run locally. Never claim a check passed without running it.

### Chunk 1 — Foundation, Design System, Data Model and Firestore Rules
- Create `plan.md` and `progress.md`. Android project (Compose, Material 3), theme, fonts, adaptive icon, MVC package skeleton.
- `FLAG_SECURE` before `setContent`, `allowBackup="false"`, `dataExtractionRules`, cleartext traffic off, Firestore `MemoryCacheSettings`, Firebase App Check (Debug provider in debug, Play Integrity in release, monitor mode).
- All Kotlin models, `PageImageStore` interface, `firestore.rules`, `firestore.indexes.json`, `firebase.json`, emulator rules tests.
- **Checkpoint 1**:
  - Rules tests: second device (wrong or missing `deviceId` claim) denied on users, access, books and pages; reader cannot read another user's `authorized_users` or `access`; reader cannot read pages of an unassigned book; reader cannot write `authorized_users`; admin cannot modify bind fields; page over 300 KB rejected; super-admin doc cannot be deleted.
  - No `signInAnonymously` / email-password auth anywhere. No Storage dependency in Gradle. App compiles.

### Chunk 2 — Express Backend and Auth Gate (end-to-end device binding)
- Backend (`/backend`): routes, controllers, services, middleware, `bind-device`, `reset-device`, `health`, rate limiter, Vercel and Render entry points, unit tests with mocked Admin SDK.
- Android: `DeviceBindingService`, `DeviceApiService` (HTTPS client, base URL from `BuildConfig`), `AuthController`, `LoginScreen`, `NotAuthorizedScreen`, `DeviceLockedScreen`, live revocation listener, role router.
- **Checkpoint 2**:
  - Backend tests: invalid token, wrong audience, unlisted Gmail, first bind, same device, different device (`DEVICE_LOCKED`), super-admin bootstrap, non-admin reset rejected, per-target and per-admin rate limits, audit log written.
  - Custom token carries exactly `deviceId`, `userEmail`, `role`. No secrets committed, `.env.example` present.
  - Android flow wired for all 4 outcomes plus revocation; app compiles.

### Chunk 3 — Reader: Bookshelf and Paginated Image Reader
- `BookService` (access-scoped lookup, 12-page cursor pagination), `FirestoreBytesPageImageStore`, `ImageCodecService` + RAM `LruCache`, `LibraryScreen`, `BookReaderScreen` (vertical scroll, pinch and double-tap zoom, prefetch, shimmer, page pill, Gmail watermark).
- **Checkpoint 3**:
  - Pagination boundaries (exactly 12, last partial batch, empty book), prefetch at 3 pages from the end, no duplicate reads when scrolling back.
  - Cache cleared on exit, sign-out and revocation. Memory-only Firestore cache confirmed. `FLAG_SECURE` active on all reader transitions. No URLs, no disk writes of page bytes. App compiles.

### Chunk 4 — Admin Portal
- Books CRUD, cover upload, page filmstrip (12-page batches), Photo Picker → on-device WebP compression (≤ 150 KB target, ≤ 300 KB enforced) → insert/delete with fractional `orderIndex` and rebalance, `pageCount` and `app_stats/storage` kept in sync, Users and access manager, Reset Device dialog (backend call), Audit sheet, Quota card, Reader Preview, debug-only Seed Loader (3 books × 15+ generated pages).
- **Checkpoint 4**:
  - Insert at start, middle, end and delete verified; rebalance logic tested; `pageCount` consistent.
  - Removing a user also deletes their `access` docs. Zero email fields written to `books`. Resets only through the backend.
  - No storage permissions in the manifest (Photo Picker only). Seed loader unreachable in release builds. App compiles.

### Chunk 5 — Website, Release Build, README and Final Audit
- `website/` (parchment style, security pillars, install guide, Download button via `config.js`).
- Release signing via `keystore.properties` (gitignored), R8 minify on, no token logging, signed release APK build (if keystore credentials exist; otherwise the exact keytool and Gradle commands).
- `README.md` with step-by-step: create Firebase project (Spark), enable Auth, create Firestore, deploy rules, register **debug and release SHA-1** and the Web client ID, backend deploy to Vercel or Render with env vars, set `API_BASE_URL`, build APK, publish to GitHub Releases, set `APK_URL`, host website (Vercel / GitHub Pages / Firebase Hosting), recovery for lost super-admin phone, "outgrowing Spark" guide, back up the keystore warning.
- **Checkpoint 5**: re-run and record all earlier checkpoint items; code-to-rules parity audit (every Firestore path used by the app and backend is covered by rules); final compile; all chunks marked in `progress.md`; list of UNVERIFIED items with commands.

---

## 9. Execution Protocol

1. Work chunk by chunk, in order. Never stop in the middle of a chunk.
2. If the output limit is near, finish the current file, update `progress.md`, tell the owner in 2 lines what is done, and ask them to type **continue**. Resume exactly from `progress.md`.
3. On finding an inconsistency in this plan, stop and report it instead of silently changing the design.
4. Do not auto-provision Firebase. Generate files and document the manual steps.

## 10. Known Constraints (state these honestly in README)

- Spark quotas are limited (section 7). Heavy usage will require Blaze.
- Vercel's free Hobby plan is meant for personal, non-commercial use. For a paid subscription business use Vercel Pro, or deploy the same backend to Render (free instances have cold starts of up to about a minute on first login).
- Android cannot block photographing the screen with another camera. The watermark only discourages it.
- `ANDROID_ID` changes after a factory reset, so the user needs an admin device reset.
- App Check (Play Integrity) runs in monitor mode because the APK is distributed outside Google Play. Test with the downloaded APK before enforcing.
- Distributing outside the Play Store means users must allow installs from the browser and may see a Play Protect warning.
