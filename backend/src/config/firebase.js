"use strict";

const admin = require("firebase-admin");
const { getFirestore } = require("firebase-admin/firestore");

let cachedServices = null;

function getFirebaseServices(env) {
  if (cachedServices) {
    return cachedServices;
  }

  if (!env.firebaseProjectId || !env.firebaseClientEmail || !env.firebasePrivateKey) {
    throw new Error(
      "Missing Firebase Admin credentials (FIREBASE_PROJECT_ID, FIREBASE_CLIENT_EMAIL, FIREBASE_PRIVATE_KEY)."
    );
  }

  const app =
    admin.apps.length > 0
      ? admin.app()
      : admin.initializeApp({
          credential: admin.credential.cert({
            projectId: env.firebaseProjectId,
            clientEmail: env.firebaseClientEmail,
            privateKey: env.firebasePrivateKey,
          }),
          projectId: env.firebaseProjectId,
        });

  const db =
    env.firestoreDatabaseId && env.firestoreDatabaseId !== "(default)"
      ? getFirestore(app, env.firestoreDatabaseId)
      : getFirestore(app);

  cachedServices = {
    app,
    db,
    auth: admin.auth(app),
    appCheck: admin.appCheck(app),
    FieldValue: admin.firestore.FieldValue,
    Timestamp: admin.firestore.Timestamp,
  };
  return cachedServices;
}

module.exports = { getFirebaseServices };
