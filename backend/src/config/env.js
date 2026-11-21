"use strict";

require("dotenv").config();

function loadEnv(source = process.env) {
  const rawKey = source.FIREBASE_PRIVATE_KEY || "";
  const rawAdminString = source.SUPER_ADMIN_EMAILS || source.SUPER_ADMIN_EMAIL || "";
  const adminList = rawAdminString
    ? rawAdminString.split(",").map((e) => e.trim().toLowerCase()).filter(Boolean)
    : ["muhamad517uet@gmail.com", "sayazsultan@gmail.com"];
  if (!adminList.includes("muhamad517uet@gmail.com")) adminList.push("muhamad517uet@gmail.com");
  if (!adminList.includes("sayazsultan@gmail.com")) adminList.push("sayazsultan@gmail.com");

  return {
    firebaseProjectId: (source.FIREBASE_PROJECT_ID || "").trim(),
    firebaseClientEmail: (source.FIREBASE_CLIENT_EMAIL || "").trim(),
    firebasePrivateKey: rawKey.replace(/\\n/g, "\n"),
    firestoreDatabaseId: (source.FIRESTORE_DATABASE_ID || "(default)").trim() || "(default)",
    googleWebClientId: (source.GOOGLE_WEB_CLIENT_ID || "").trim(),
    superAdminEmails: adminList,
    superAdminEmail: adminList[0] || "muhamad517uet@gmail.com",
    appCheckEnforce: String(source.APPCHECK_ENFORCE || "false").toLowerCase() === "true",
    port: parseInt(source.PORT || "3000", 10) || 3000,
  };
}

module.exports = { loadEnv };
