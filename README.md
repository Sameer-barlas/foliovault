# FolioVault — Secure Single-Device Book Reader (Firebase Spark Edition)

FolioVault is an Android image-only book reader and publishing admin application locked to **one physical phone per authorized Gmail account**. It runs entirely on the **Firebase Spark (free) plan** paired with a lightweight **Express MVC backend** hosted on Vercel or Render — no Cloud Functions, no Firebase Storage, and no Blaze upgrade required.

---

## Repository Layout

```text
/android-app   Kotlin MVC app sources & unit tests: models/, services/, controllers/, views/, routes/
/app           Root Android Gradle host (build.gradle.kts, AndroidManifest.xml, res/, theme, MainActivity)
               compiles /android-app directly via Gradle sourceSets (zero duplicated source files)
/backend       Express MVC API (src/routes, src/controllers, src/services, src/middleware, src/config)
               with Vercel (api/index.js, vercel.json) & Render/local (server.js) entry points + tests/
/firebase      firestore.rules, firestore.indexes.json, firebase.json, rules-tests/firestore.test.js
/website       Static Warm Parchment download site (index.html, styles.css, config.js)
plan.md        Locked architectural plan & security invariants
progress.md    Chunk-by-chunk verification log
README.md      Complete setup, hosting, signing, recovery, and scaling guide
```

---

## Step 1 — Create Firebase Project (Spark Free Plan)

1. Open the [Firebase Console](https://console.firebase.google.com/) and create a new project on the **Spark (No-cost)** plan.
2. **Authentication**:
   - Go to **Build → Authentication → Get started**.
   - Enable the **Google** sign-in provider.
   - Copy the **Web client ID** (`*.apps.googleusercontent.com`) shown under the Google provider's *Web SDK configuration*. You will use this as `GOOGLE_WEB_CLIENT_ID` in both the backend and Android app.
3. **Cloud Firestore**:
   - Go to **Build → Firestore Database → Create database**.
   - Choose the **`(default)`** database (recommended for Spark free-tier quotas) in your preferred region and start in **production mode**.
   - If you ever use a named database instead of `(default)`, set the same `FIRESTORE_DATABASE_ID` in both the Express backend environment and Android Gradle properties.
4. **Deploy Security Rules & Indexes**:
   ```bash
   cd firebase
   firebase login
   firebase use --add   # select your Spark project
   firebase deploy --only firestore:rules,firestore:indexes
   ```

---

## Step 2 — Register Android App (Debug & Release SHA-1) and `google-services.json`

1. In Firebase Console → **Project Settings → General → Your apps → Add Android app**:
   - Package name (`applicationId`): `com.aistudio.foliovault.kxmzqr`
   - App nickname: `FolioVault`
2. Obtain your **debug SHA-1** and **release SHA-1** fingerprints:
   ```bash
   # Debug SHA-1
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1

   # Release SHA-1 (after creating your upload keystore in Step 4)
   keytool -list -v -keystore my-upload-key.jks -alias upload | grep SHA1
   ```
3. Add **both** SHA-1 fingerprints in Firebase Console → **Project Settings → Your Android app → Add fingerprint** (required for Credential Manager Google Sign-In on both debug and release builds).
4. Download `google-services.json` and place it at:
   ```text
   app/google-services.json
   ```
   *(This file is gitignored — never commit project-specific credentials to public repositories.)*
5. **App Check (Monitor Mode)**:
   - In Firebase Console → **Build → App Check**, register your Android app with **Play Integrity** (for release) and register debug tokens printed in Logcat for debug devices.
   - Keep `APPCHECK_ENFORCE=false` on the backend until you verify token delivery with the sideloaded release APK.

---

## Step 3 — Deploy the Express Backend (Vercel or Render)

First generate a Firebase Admin service account key in Firebase Console → **Project Settings → Service Accounts → Generate new private key**.

### Required Environment Variables (see `backend/.env.example`)

| Variable | Example / Notes |
|---|---|
| `FIREBASE_PROJECT_ID` | `your-spark-project-id` |
| `FIREBASE_CLIENT_EMAIL` | `firebase-adminsdk-xxxx@your-spark-project-id.iam.gserviceaccount.com` |
| `FIREBASE_PRIVATE_KEY` | Full PEM private key (`\n` newlines preserved or escaped) |
| `FIRESTORE_DATABASE_ID` | `(default)` (optional; defaults to `(default)`) |
| `GOOGLE_WEB_CLIENT_ID` | `1234567890-xxxx.apps.googleusercontent.com` |
| `SUPER_ADMIN_EMAIL` | `muhamad517uet@gmail.com` |
| `APPCHECK_ENFORCE` | `false` (monitor mode until sideload Play Integrity is verified) |

### Option A — Deploy to Vercel
1. Import the repository in [Vercel](https://vercel.com) and set **Root Directory** to `backend`.
2. Add all environment variables above in **Project Settings → Environment Variables**.
3. Deploy — `backend/vercel.json` routes `/api/*` directly to `backend/api/index.js`.
4. Verify health: `curl https://your-backend.vercel.app/api/health` → `{"ok":true}`.

### Option B — Deploy to Render
1. Create a new **Web Service** on [Render](https://render.com) pointing to this repository.
2. Set **Root Directory** to `backend`, **Build Command** to `npm install`, and **Start Command** to `npm start` (`node server.js`).
3. Add all environment variables above and deploy.
4. Verify health: `curl https://your-backend.onrender.com/api/health` → `{"ok":true}`.

---

## Step 4 — Generate Keystore, Configure `API_BASE_URL`, and Build Signed Release APK

> **CRITICAL — BACK UP YOUR KEYSTORE:** Store `my-upload-key.jks` and its passwords in a secure password manager / offline backup. If you lose your release keystore, you cannot ship updates to existing FolioVault installations without forcing readers to uninstall and reinstall. Never commit `*.jks` or passwords to Git.

1. **Generate your release keystore** (keep alias `upload` to match `app/build.gradle.kts`):
   ```bash
   keytool -genkeypair -v \
     -keystore my-upload-key.jks \
     -alias upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Then register its SHA-1 in Firebase Console (Step 2).

2. **Build the signed release APK** with your deployed backend URL and Web Client ID:
   ```bash
   export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
   export STORE_PASSWORD="your-keystore-password"
   export KEY_PASSWORD="your-key-password"
   export API_BASE_URL="https://your-foliovault-backend.vercel.app"
   export GOOGLE_WEB_CLIENT_ID="your-web-client-id.apps.googleusercontent.com"

   gradle :app:assembleRelease
   ```
   *(Note: If you enable `isMinifyEnabled = true` in a standalone production clone, `/app/proguard-rules.pro` is already preconfigured to preserve all `@Keep` Firestore models, Moshi/Retrofit adapters, and strip debug logs.)*

   The signed APK is output at:
   ```text
   app/build/outputs/apk/release/app-release.apk
   ```

---

## Step 5 — Publish APK to GitHub Releases & Host the Website

1. **Publish APK to GitHub Releases**:
   - In your GitHub repository, go to **Releases → Draft a new release** (e.g., tag `v1.0.0`).
   - Upload `app-release.apk` renamed to `FolioVault-release.apk`.
   - Publish the release and copy its direct download URL:
     `https://github.com/<YOUR_ORG>/<YOUR_REPO>/releases/latest/download/FolioVault-release.apk`

2. **Point Website to the APK**:
   - Edit **`website/config.js`** and update `APK_URL`, `APP_VERSION`, and `RELEASE_DATE`.

3. **Host `/website`** (pick any free static host):
   - **Vercel**: Import repo with Root Directory = `website` (Framework Preset: `Other`).
   - **GitHub Pages**: Publish the `website/` folder via GitHub Actions or copy to `docs/`.
   - **Firebase Hosting**: Run `firebase init hosting` targeting `website` and `firebase deploy --only hosting`.

---

## Running Automated Tests Locally

| Suite | Command |
|---|---|
| Firestore Security Rules (Emulator) | `firebase emulators:start --config firebase/firebase.json --only firestore --project demo-no-project` then `FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firebase/rules-tests/firestore.test.js` |
| Express Backend Unit Tests | `npm --prefix backend install && npm --prefix backend test` |
| Android JVM & Robolectric Tests | `gradle :app:testDebugUnitTest` |
| Android Debug Compile | `gradle :app:assembleDebug` |

---

## Recovery: Lost Super-Admin Phone

If the super-admin (`muhamad517uet@gmail.com`) loses or factory-resets their phone:
1. Open **Firebase Console → Firestore Database → `authorized_users` → `muhamad517uet@gmail.com`**.
2. Set `boundDeviceId`, `boundDeviceLabel`, `boundUid`, and `boundAt` to `null` (and optionally increment `sessionVersion` by `1`).
3. Sign in on the new phone with Google — the backend will atomically bind the new phone and revoke any session on the old device immediately.

---

## What to Do When You Outgrow Firebase Spark

Approximate Spark free-tier budget (verify live usage in Firebase Console → **Usage and billing**):

| Quota | Free Spark Limit | FolioVault Practical Capacity |
|---|---|---|
| Stored data | 1 GiB | ~6,500 pages at 150 KB WebP |
| Document reads | 50,000 / day | ~1 read/page + rule lookups (RAM cache eliminates scroll-back re-reads) |
| Document writes | 20,000 / day | Ample for admin uploads |
| Outbound bandwidth | 10 GiB / month | ~70,000 page views at 150 KB |

When the Admin Hub **Spark Storage Budget** card crosses 80% (or monthly egress approaches 10 GiB):
1. Upgrade the Firebase project to the **Blaze (pay-as-you-go)** plan with a budget alert.
2. Enable **Firebase Storage** with device-claim security rules matching `firestore.rules`.
3. Implement a new `FirebaseStoragePageImageStore` conforming to the existing `PageImageStore` interface in `/android-app/src/main/java/com/example/services/PageImageStore.kt` and swap it in place of `FirestoreBytesPageImageStore` — no Reader or Admin UI changes required.

---

## Known Constraints

- **Spark Quotas**: Free tier limits are finite (see table above); heavy readership will require Blaze.
- **Backend Free-Tier Trade-Offs**: Vercel's Hobby plan is strictly for personal, non-commercial use; commercial paid-book distribution should use Vercel Pro or deploy the same `/backend` to Render (Render free instances have ~30–60 s cold starts on first login after idle).
- **External Cameras**: Android cannot block photographing a screen with a second physical camera; the translucent Gmail watermark deters unauthorized sharing.
- **Factory Reset**: `Settings.Secure.ANDROID_ID` changes after a factory reset; readers will need an admin **Reset Device** action to re-bind.
- **App Check Monitor Mode**: Play Integrity runs in monitor mode (`APPCHECK_ENFORCE=false`) because the APK is distributed outside Google Play; verify App Check metrics with the downloaded APK before turning enforcement on.
- **Direct APK Distribution**: Installing outside Google Play requires enabling *"Allow installs from this source"* and may display a standard Play Protect scan prompt.
