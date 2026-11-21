# FolioVault — Secure Single-Device Book Reader (Firebase Spark Edition)

FolioVault is an Android image-book reader and publishing admin app designed for authors, coaching institutes, and private publishers who distribute paid visual study material locked to one physical phone per authorized Gmail account. The system runs entirely on the Firebase Spark (free) plan paired with a lightweight Express backend deployable to Vercel or Render—requiring no Cloud Functions, no Firebase Storage, and no Blaze upgrade.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The four environment-alignment decisions below were confirmed during discovery and govern the build alongside the 12 locked specification decisions.

- **Confirmed Decision 1 — Project Structure & Build Compatibility**: All FolioVault Kotlin MVC layers (models, services, controllers, views, routes, and tests) will live inside a dedicated `android-app` folder matching your repository layout, while the root Android Gradle host connects to those sources directly so AI Studio incremental builds and streaming preview remain fully functional without duplicating any code. The companion Express backend, Firebase rules/tests, and static website will live alongside it in their own top-level modules, plus `plan.md`, `progress.md`, and `README.md`.
- **Confirmed Decision 2 — Release Signing Compatibility**: Existing AI Studio Gradle signing configurations will remain untouched so platform builds stay intact. Release builds will support environment-variable signing (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) with R8 minification/obfuscation rules and complete `keytool` + Gradle release commands documented in the README.
- **Confirmed Decision 3 — Manual Firebase Setup**: Firebase will not be auto-provisioned. All security rules, index configurations, emulator rule tests, and manual Firebase Console setup steps (Spark project creation, Auth, Firestore, debug/release SHA-1, Web Client ID, App Check) will be generated and documented cleanly.
- **Confirmed Decision 4 — Continuous Chunk Execution**: Once approved, implementation will proceed continuously chunk by chunk (Chunks 1 through 5) until complete or an output boundary is reached, running and recording every checkpoint item in `progress.md` as `PASS`, `FAIL`, or `UNVERIFIED` (with exact local commands for anything unavailable in this environment).

---

## 1. Overview & Core Concept

- **What It Does**: FolioVault delivers private, image-only books to authorized Gmail readers while enforcing strict one-device-per-account locking on the server. Administrators operate inside the same app to curate books, upload and reorder pages via the Android Photo Picker, grant or revoke reader access, monitor Spark storage consumption, audit resets, and preview the reader experience.
- **Target Audience / Persona**: Independent authors, exam-prep coaching institutes, and paid-material publishers who need leak-resistant distribution without paying for DRM SaaS or cloud storage egress.
- **Key Value**: Combines server-enforced single-device cryptographic session claims, zero-disk-cache rendering, screenshot protection, dynamic Gmail watermarking, and aggressive on-device WebP compression so up to ~6,500 pages fit comfortably inside Firebase Spark's 1 GiB free tier.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Welcome & Single-Device Auth Gate**:
   - Users land on a parchment editorial welcome screen featuring a single **Continue with Google** action powered by Jetpack Credential Manager (no passwords, no anonymous sign-in).
   - The app sends the Google ID token, hardware-derived device ID, human-readable device label, and App Check token over HTTPS to the Express backend.
   - Four deterministic outcomes are handled gracefully:
     - **Not Authorized (`403`)**: Dedicated *Access Not Granted* screen showing the attempted Gmail and administrator contact guidance.
     - **First Sign-In or Same Device (`200`)**: Backend atomically binds the device and mints a Firebase custom token with claims `deviceId`, `userEmail`, and `role`; the app signs in and routes to the Reader Library or Admin Hub.
     - **Different Device (`409`)**: Dedicated *Device Locked* screen displaying only the bound device label (for example, *"Linked to Pixel 8"*) without leaking sensitive identifiers.
     - **Live Revocation Guard**: An active listener monitors the user's own authorization record; if deleted, reset, or denied, the app immediately wipes the in-memory bitmap cache, signs out, and returns to the appropriate gate screen.
2. **Reader Bookshelf & Paginated Reader**:
   - **Bookshelf**: Loads only books explicitly granted to the reader's Gmail in a clean 2-column cover grid with pull-to-refresh, title/author search, and tailored empty states.
   - **Vertical Image Reader**: Streams pages in 12-page cursor batches ordered by `orderIndex`, prefetching the next batch when the reader is within 3 pages of the end. Decoded bitmaps live strictly in an in-memory `LruCache` so scrolling backward incurs zero extra Firestore reads. Includes pinch-to-zoom, double-tap zoom, shimmer placeholders, a floating `04 / 18` monospace page pill, and a subtle diagonal translucent Gmail watermark across every page.
3. **Admin Hub (Books, Users, Audit & Reader Preview)**:
   - **Books Tab**: Create, edit, and delete books; pick and compress covers (≤ 60 KB WebP); inspect pages in a 12-page paginated filmstrip; insert pages from the zero-permission Android Photo Picker at the start, end, or before/after any specific page; delete pages with confirmation; and monitor approximate stored bytes against the 1 GiB Spark quota with an 80% warning banner.
   - **Users Tab**: Add or remove reader Gmail accounts, toggle per-book access grants, inspect bound device labels and timestamps, and trigger rate-limited server-side **Reset Device** actions with an optional audit reason.
   - **Audit Sheet**: Displays recent device reset audit records (target email, previous device label, acting admin, reason, timestamp).
   - **Reader Preview & Debug Seed Loader**: Admins can toggle into the Reader experience to inspect books; in debug builds only (`BuildConfig.DEBUG && isAdmin`), a Seed Loader generates 3 sample books with 15+ Canvas-rendered parchment pages compressed to WebP.
4. **Companion Download Website**:
   - A static Warm Parchment landing page introducing FolioVault's security pillars, Android sideload installation guide (*"Allow installs from this source"*), and a prominent **Download APK** button driven by a single configurable GitHub Releases URL.

### Visual Identity & Theme

- **Aesthetic Direction**: **Warm Parchment Editorial** — tactile bookshop warmth paired with restrained archival typography and high-contrast ink legibility. In reading mode, the surround transitions to a deep archival charcoal canvas so page artwork commands total focus.
- **Color Palette & Surface Hierarchy**:
  - *Primary Canvas*: Warm Parchment `#FAF6EE`
  - *Elevated Cards & Surfaces*: Soft Vellum `#F3ECE0` / `#EFE7D8`
  - *Primary Ink*: Deep Espresso Ink `#1C1917`
  - *Primary Accent (Actions & Active States)*: Editorial Oxblood `#7C2D2D` with `#FFFBF5` foreground
  - *Secondary Accent (Badges & Highlights)*: Antique Brass `#8B6B3E`
  - *Muted Metadata*: Warm Slate `#5C544B` | *Hairline Dividers*: Parchment Rule `#DDD4C4`
  - *Semantic Status*: Forest Success `#2E5E4E` | Crimson Alert `#A62626`
  - *Reader Surround*: Archival Dark `#141210`
- **Typography & Hierarchy** (bundled locally in `res/font/`, zero runtime font downloads):
  - *Display & Book Titles*: **Playfair Display** for editorial authority and literary character.
  - *UI Labels & Body Copy*: **Plus Jakarta Sans** for crisp modern readability across compact cards and controls.
  - *Counters, Device Labels & Quotas*: **JetBrains Mono** for page pills (`04 / 18`), storage gauges, and device metadata.
- **Adaptive Layout & Motion**:
  - Edge-to-edge Material 3 with full `WindowInsets` safe-area handling (`enableEdgeToEdge()`).
  - Compact screens use a bottom navigation bar; medium/expanded tablets switch to a `NavigationRail` and constrain form/detail widths cleanly.
  - Custom adaptive launcher icon aligned to the Warm Parchment & Oxblood identity.
  - Minimum 48 dp touch targets everywhere, crisp Material ripples, spring-based transitions under 300 ms, shimmer loading states, and snackbars for errors and backend rate-limit countdowns.

---

## 3. Key Product Decisions & Trade-Offs

- **Server-Enforced Custom Token Claims (`deviceId`, `userEmail`, `role`)**:
  - *Chosen Approach*: The Express backend verifies Google ID tokens with `google-auth-library`, atomically binds the physical device in Firestore, and mints a Firebase custom token carrying strictly `deviceId`, `userEmail`, and `role`. Firestore rules reject any request whose `deviceId` claim no longer matches the live `boundDeviceId`.
  - *Why*: Guarantees instant revocation on reset or user deletion without relying on client-side honesty or paid Cloud Functions.
  - *Alternatives Considered*: Client-side device checks (trivially bypassed) or Cloud Functions (requires Blaze billing).
- **Firestore WebP Byte Storage via `PageImageStore` Abstraction**:
  - *Chosen Approach*: Compress covers (≤ 60 KB) and pages (~1080 px wide, target ≤ 150 KB, hard max 300 KB) on-device to WebP and store them directly as Firestore `Bytes` fields behind a `PageImageStore` interface (`FirestoreBytesPageImageStore`).
  - *Why*: Eliminates Firebase Storage, public download URLs, and Blaze requirements while keeping a single-class seam to migrate to Storage if usage outgrows Spark's 1 GiB / 10 GiB-per-month free quotas.
- **Zero-Disk-Cache Content Protection**:
  - *Chosen Approach*: Configure Firestore with `MemoryCacheSettings`, keep decoded bitmaps exclusively in a RAM `LruCache` cleared on reader exit/sign-out/revocation, apply `FLAG_SECURE` before `setContent`, set `allowBackup="false"`, exclude all domains in `dataExtractionRules`, disable cleartext traffic, and use Android's zero-permission Photo Picker.
  - *Why*: Prevents casual screenshots, ADB/cloud backup extraction, and persistent image files on device storage while staying Play-policy compliant.
- **Fractional `orderIndex` with Automatic Rebalancing**:
  - *Chosen Approach*: Assign page indices at 1000, 2000, 3000…; insert between A and B at `(A + B) / 2`; automatically rebalance all pages in a single batched write if the gap falls below `0.001`.
  - *Why*: Allows O(1) page insertion anywhere in a book without rewriting every page document on each upload.
- **Known Operational Constraints (Documented Transparently in README)**:
  - Spark free limits (~1 GiB storage ≈ 6,500 pages at 150 KB, 50K reads/day, 20K writes/day, 10 GiB/month egress); Vercel Hobby is non-commercial while Render free instances incur cold starts; hardware cameras cannot be blocked (watermark deters sharing); `ANDROID_ID` resets on factory reset (resolved via admin reset); App Check runs in monitor mode until tested with the sideloaded APK.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────────┐
│                   Android App (FLAG_SECURE · App Check)                    │
│  ┌──────────────────┐   ┌────────────────────┐   ┌──────────────────────┐  │
│  │ Compose Views    │──▶│ Controllers (VMs)  │──▶│ Services & Models    │  │
│  │ • Auth Gate      │   │ • AuthController   │   │ • DeviceBindingSvc   │  │
│  │ • Reader Library │   │ • LibraryController│   │ • DeviceApiService   │  │
│  │ • Book Reader    │   │ • ReaderController │   │ • BookService        │  │
│  │ • Admin Hub      │   │ • AdminController  │   │ • PageImageStore     │  │
│  │ • Audit & Quota  │   │ • AuditController  │   │ • ImageCodec + RAM   │  │
│  └──────────────────┘   └────────────────────┘   └──────────┬───────────┘  │
└─────────────────────────────────────────────────────────────┼──────────────┘
                        │ HTTPS (Google ID Token /            │ Firebase SDK
                        │ Firebase ID Token + App Check)      │ (Custom Token,
                        ▼                                     │  Memory Cache)
┌──────────────────────────────────────────────┐              │
│   Express Backend (Vercel / Render MVC)      │              │
│   Routes ─▶ Controllers ─▶ Services          │              │
│   • GET  /api/health                         │              │
│   • POST /api/bind-device  (Atomic bind)     │              │
│   • POST /api/reset-device (Rate-limited)    │              │
└──────────────────────┬───────────────────────┘              │
                       │ Firebase Admin SDK                   ▼
                       └─────────────────────▶┌──────────────────────────────┐
                                              │  Firebase Auth + Firestore   │
                                              │  (Spark Plan · Strict Rules) │
                                              │  • authorized_users/{email}  │
                                              │  • access/{email__bookId}    │
                                              │  • books/{bookId}            │
                                              │  • books/{id}/pages/{pageId} │
                                              │  • reset_audit_logs/{logId}  │
                                              │  • rate_limits/{key}         │
                                              │  • app_stats/storage         │
                                              └──────────────────────────────┘
```

### Data Model & Zero-Trust Rules

- **`authorized_users/{email}`** (doc ID = lowercase Gmail): Stores `email`, `displayName`, `role` (`admin`|`reader`), server-managed bind fields (`boundDeviceId`, `boundDeviceLabel`, `boundUid`, `boundAt`), `sessionVersion`, timestamps. Readers can `get` only their own doc (enabling the live revocation listener) and can never write; admins can create `reader` accounts with `boundDeviceId == null`, edit only `displayName`/`updatedAt`, and delete non-super-admin accounts. Super-admin (`muhamad517uet@gmail.com`) is bootstrapped by the backend and protected from deletion/demotion.
- **`access/{email__bookId}`**: Decouples grants from books so book documents never contain subscriber emails. Readers query only their own email with a matching `deviceId` claim; admins manage grants for existing books.
- **`books/{bookId}` & `books/{bookId}/pages/{pageId}`**: Books hold metadata plus `coverBytes` (≤ 60 KB) and `pageCount`. Pages hold `orderIndex` (Double), `imageBytes` (WebP ≤ 300 KB), dimensions, caption, and timestamps. Readers may only read books/pages where `hasMatchingDevice()` and `hasGrant(bookId)` both hold.
- **`reset_audit_logs/{logId}` & `rate_limits/{key}` & `app_stats/storage`**: Audit logs are admin-read-only and backend-write-only; rate limits (3/target/24h, 20/admin/hr) are backend-only; storage stats are admin-read/write.
- **Database Constant**: Both Android and Express read from a single `FIRESTORE_DATABASE_ID` constant defaulting to `(default)`.

### Interactive Component & State Mapping

- **Auth Gate Flow**: Tapping *Continue with Google* launches Credential Manager (`GetSignInWithGoogleOption`), posts credentials to `/api/bind-device`, maps HTTP 200/403/409/network errors into sealed UI states, signs in via `signInWithCustomToken`, and attaches the `authorized_users/{email}` snapshot listener. Any revocation or sign-out immediately flushes the RAM `LruCache`, detaches listeners, and resets navigation cleanly.
- **Reader Flow**: Bookshelf subscribes/fetches grants for `userEmail`, resolves each granted book card, filters locally via search input, and opens the Reader. The Reader tracks visible item index to update the floating `XX / YY` pill, triggers next-batch cursor fetches (`limit(12)`, `startAfter`) at 3 pages from the end, decodes WebP bytes off the main thread into `LruCache`, supports pinch/double-tap zoom gestures, and wipes cache on dispose.
- **Admin Flow**: Books tab manages create/edit/delete dialogs, cover picking + WebP compression, paginated page filmstrip, positional insert (start/before/after/end) with automatic fractional-index rebalancing, and keeps `pageCount` + `app_stats/storage` synchronized. Users tab handles adding/removing readers (cascading `access` doc cleanup on user removal), toggling book grants, opening the Reset Device confirmation dialog (calling `/api/reset-device` and surfacing `429` retry timers via snackbar), viewing the Audit sheet, entering Reader Preview, and running the debug-only Seed Loader.

### Security Safeguards

- Server-side Google ID token audience verification and custom-token claim minting prevent client-side spoofing of `deviceId`, `userEmail`, or `role`.
- Firestore security rules enforce device-claim parity on every read/write, strict payload schemas, byte-size ceilings (60 KB cover / 300 KB page), and zero client access to rate-limit or audit writes.
- No sensitive tokens or secrets are committed or logged; backend credentials stay strictly in environment variables (`.env.example` documented), and Android traffic enforces HTTPS with cleartext disabled and `FLAG_SECURE` enabled before composition.

### 5-Chunk Execution & Checkpoint Roadmap

1. **Chunk 1 — Foundation, Design System, Data Model & Firestore Rules**: `plan.md`, `progress.md`, Warm Parchment M3 theme, bundled local fonts, custom adaptive icon, MVC package skeleton under `android-app`, security hardening (`FLAG_SECURE`, `allowBackup="false"`, `dataExtractionRules`, cleartext off, `MemoryCacheSettings`, App Check), Kotlin models, `PageImageStore`, `firestore.rules`, `firestore.indexes.json`, `firebase.json`, and emulator rules test suite.
2. **Chunk 2 — Express Backend & Auth Gate**: Express MVC backend (`health`, `bind-device`, `reset-device`, rate limiter, Vercel/Render entry points, mocked Admin SDK unit tests) + Android Credential Manager sign-in, HTTPS API client, Welcome / Not Authorized / Device Locked screens, live revocation guard, and role router.
3. **Chunk 3 — Reader Bookshelf & Paginated Image Reader**: Access-scoped `BookService`, `FirestoreBytesPageImageStore`, background WebP decoding + RAM `LruCache`, Bookshelf grid with search/refresh, and vertical paginated reader with prefetch, pinch/double-tap zoom, shimmer, floating page pill, and Gmail watermark.
4. **Chunk 4 — Admin Portal**: Books CRUD, cover/page Photo Picker + on-device WebP compression, paginated filmstrip, positional insert/delete with fractional `orderIndex` rebalancing, `pageCount`/`app_stats` sync, Users & Access manager, backend Reset Device flow, Audit sheet, Spark Quota card, Reader Preview, and debug-only Canvas Seed Loader.
5. **Chunk 5 — Website, Release Build Config, README & Final Audit**: Static Warm Parchment website with configurable `APK_URL`, release R8/Proguard rules + env-var signing verification, comprehensive step-by-step `README.md`, code-to-rules parity audit, full checkpoint re-verification, and final `progress.md` status report.
