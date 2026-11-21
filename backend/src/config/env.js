"use strict";

require("dotenv").config();

function loadEnv(source = process.env) {
  const rawKey = source.FIREBASE_PRIVATE_KEY || "";
  return {
    firebaseProjectId: (source.FIREBASE_PROJECT_ID || "").trim(),
    firebaseClientEmail: (source.FIREBASE_CLIENT_EMAIL || "").trim(),
    firebasePrivateKey: rawKey.replace(/\\n/g, "\n"),
    firestoreDatabaseId: (source.FIRESTORE_DATABASE_ID || "(default)").trim() || "(default)",
    googleWebClientId: (source.GOOGLE_WEB_CLIENT_ID || "").trim(),
    superAdminEmail: (source.SUPER_ADMIN_EMAIL || "muhamad517uet@gmail.com").trim().toLowerCase(),
    appCheckEnforce: String(source.APPCHECK_ENFORCE || "false").toLowerCase() === "true",
    port: parseInt(source.PORT || "3000", 10) || 3000,
  };
}

module.exports = { loadEnv };
