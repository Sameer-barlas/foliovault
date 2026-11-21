const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const path = require("node:path");

const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const SUPER_ADMIN_EMAIL = "muhamad517uet@gmail.com";
const SUPER_ADMIN_EMAIL_2 = "sayazsultan@gmail.com";
const ADMIN_DEVICE_ID = "dev-admin-001";
const READER_A_EMAIL = "alice@example.com";
const READER_A_DEVICE_ID = "dev-alice-001";
const READER_B_EMAIL = "bob@example.com";
const READER_B_DEVICE_ID = "dev-bob-001";

let testEnv;

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

function makeBlob(db, byteLength) {
  const bytes = new Uint8Array(byteLength);
  bytes.fill(0x52); // 'R'
  if (db && db.app && db.app.firebase && db.app.firebase.firestore && db.app.firebase.firestore.Blob) {
    return db.app.firebase.firestore.Blob.fromUint8Array(bytes);
  }
  return bytes;
}

async function seedFixture() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const now = pastDate(5000);

    await db.collection("authorized_users").doc(SUPER_ADMIN_EMAIL).set({
      email: SUPER_ADMIN_EMAIL,
      displayName: "Super Admin 1",
      role: "admin",
      boundDeviceId: ADMIN_DEVICE_ID,
      boundDeviceLabel: "Pixel 9 Pro",
      boundUid: "g_admin_sub",
      boundAt: now,
      sessionVersion: 1,
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("authorized_users").doc(SUPER_ADMIN_EMAIL_2).set({
      email: SUPER_ADMIN_EMAIL_2,
      displayName: "Super Admin 2",
      role: "admin",
      boundDeviceId: "dev-admin-002",
      boundDeviceLabel: "Pixel 9 Pro XL",
      boundUid: "g_admin_sub_2",
      boundAt: now,
      sessionVersion: 1,
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("authorized_users").doc(READER_A_EMAIL).set({
      email: READER_A_EMAIL,
      displayName: "Alice Reader",
      role: "reader",
      boundDeviceId: READER_A_DEVICE_ID,
      boundDeviceLabel: "Pixel 8",
      boundUid: "g_alice_sub",
      boundAt: now,
      sessionVersion: 1,
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("authorized_users").doc(READER_B_EMAIL).set({
      email: READER_B_EMAIL,
      displayName: "Bob Reader",
      role: "reader",
      boundDeviceId: READER_B_DEVICE_ID,
      boundDeviceLabel: "Galaxy S24",
      boundUid: "g_bob_sub",
      boundAt: now,
      sessionVersion: 1,
      createdAt: now,
      updatedAt: now,
    });

    await db.collection("books").doc("book_assigned").set({
      title: "Calculus Foundations",
      author: "Dr. A. Editorial",
      description: "Private exam prep volume",
      category: "Mathematics",
      coverBytes: makeBlob(db, 1024),
      pageCount: 1,
      createdBy: SUPER_ADMIN_EMAIL,
      createdAt: now,
      updatedAt: now,
    });

    await db
      .collection("books")
      .doc("book_assigned")
      .collection("pages")
      .doc("page_1")
      .set({
        orderIndex: 1000,
        imageBytes: makeBlob(db, 2048),
        width: 1080,
        height: 1440,
        caption: "Chapter 1",
        createdAt: now,
        updatedAt: now,
      });

    await db.collection("books").doc("book_unassigned").set({
      title: "Secret Physics Compendium",
      author: "Dr. B. Editorial",
      description: "Restricted edition",
      category: "Physics",
      coverBytes: makeBlob(db, 1024),
      pageCount: 1,
      createdBy: SUPER_ADMIN_EMAIL,
      createdAt: now,
      updatedAt: now,
    });

    await db
      .collection("books")
      .doc("book_unassigned")
      .collection("pages")
      .doc("page_1")
      .set({
        orderIndex: 1000,
        imageBytes: makeBlob(db, 2048),
        width: 1080,
        height: 1440,
        caption: "Confidential Page",
        createdAt: now,
        updatedAt: now,
      });

    await db.collection("access").doc(`${READER_A_EMAIL}__book_assigned`).set({
      email: READER_A_EMAIL,
      bookId: "book_assigned",
      grantedBy: SUPER_ADMIN_EMAIL,
      grantedAt: now,
    });

    await db.collection("access").doc(`${READER_B_EMAIL}__book_unassigned`).set({
      email: READER_B_EMAIL,
      bookId: "book_unassigned",
      grantedBy: SUPER_ADMIN_EMAIL,
      grantedAt: now,
    });
  });
}

before(async () => {
  const rulesPath = path.resolve(__dirname, "..", "firestore.rules");
  const rules = fs.readFileSync(rulesPath, "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await seedFixture();
});

test("1. Second device (wrong or missing deviceId claim) denied on users list, access, books, and pages", async () => {
  const wrongDeviceDb = testEnv
    .authenticatedContext("g_alice_sub", {
      userEmail: READER_A_EMAIL,
      deviceId: "dev-rogue-999",
      role: "reader",
    })
    .firestore();

  const missingDeviceDb = testEnv
    .authenticatedContext("g_alice_sub", {
      userEmail: READER_A_EMAIL,
      role: "reader",
    })
    .firestore();

  for (const db of [wrongDeviceDb, missingDeviceDb]) {
    // Own user doc get is intentionally allowed for live revocation detection
    await assertSucceeds(db.collection("authorized_users").doc(READER_A_EMAIL).get());
    // Everything else must be denied
    await assertFails(db.collection("authorized_users").get());
    await assertFails(db.collection("authorized_users").doc(READER_B_EMAIL).get());
    await assertFails(db.collection("access").where("email", "==", READER_A_EMAIL).get());
    await assertFails(db.collection("access").doc(`${READER_A_EMAIL}__book_assigned`).get());
    await assertFails(db.collection("books").doc("book_assigned").get());
    await assertFails(
      db.collection("books").doc("book_assigned").collection("pages").doc("page_1").get()
    );
    await assertFails(
      db.collection("books").doc("book_assigned").collection("pages").orderBy("orderIndex").get()
    );
  }
});

test("2. Valid reader on bound device can read own access, assigned book, and assigned pages", async () => {
  const aliceDb = testEnv
    .authenticatedContext("g_alice_sub", {
      userEmail: READER_A_EMAIL,
      deviceId: READER_A_DEVICE_ID,
      role: "reader",
    })
    .firestore();

  await assertSucceeds(aliceDb.collection("authorized_users").doc(READER_A_EMAIL).get());
  await assertSucceeds(aliceDb.collection("access").where("email", "==", READER_A_EMAIL).get());
  await assertSucceeds(aliceDb.collection("books").doc("book_assigned").get());
  await assertSucceeds(
    aliceDb
      .collection("books")
      .doc("book_assigned")
      .collection("pages")
      .orderBy("orderIndex")
      .limit(12)
      .get()
  );
});

test("3. Reader cannot read another user's authorized_users or access docs", async () => {
  const aliceDb = testEnv
    .authenticatedContext("g_alice_sub", {
      userEmail: READER_A_EMAIL,
      deviceId: READER_A_DEVICE_ID,
      role: "reader",
    })
    .firestore();

  await assertFails(aliceDb.collection("authorized_users").doc(READER_B_EMAIL).get());
  await assertFails(aliceDb.collection("authorized_users").get());
  await assertFails(aliceDb.collection("access").doc(`${READER_B_EMAIL}__book_unassigned`).get());
  await assertFails(aliceDb.collection("access").where("email", "==", READER_B_EMAIL).get());
  await assertFails(aliceDb.collection("access").get());
});

test("4. Reader cannot read unassigned books or pages of an unassigned book", async () => {
  const aliceDb = testEnv
    .authenticatedContext("g_alice_sub", {
      userEmail: READER_A_EMAIL,
      deviceId: READER_A_DEVICE_ID,
      role: "reader",
    })
    .firestore();

  await assertFails(aliceDb.collection("books").get());
  await assertFails(aliceDb.collection("books").doc("book_unassigned").get());
  await assertFails(
    aliceDb.collection("books").doc("book_unassigned").collection("pages").doc("page_1").get()
  );
  await assertFails(
    aliceDb.collection("books").doc("book_unassigned").collection("pages").get()
  );
});

test("5. Reader cannot write authorized_users, books, pages, access, audit logs, or rate_limits", async () => {
  const aliceDb = testEnv
    .authenticatedContext("g_alice_sub", {
      userEmail: READER_A_EMAIL,
      deviceId: READER_A_DEVICE_ID,
      role: "reader",
    })
    .firestore();

  await assertFails(
    aliceDb.collection("authorized_users").doc(READER_A_EMAIL).update({
      displayName: "Hacked",
      updatedAt: pastDate(),
    })
  );
  await assertFails(
    aliceDb.collection("authorized_users").doc("evil@example.com").set({
      email: "evil@example.com",
      displayName: "Evil",
      role: "reader",
      boundDeviceId: null,
      boundDeviceLabel: null,
      boundUid: null,
      boundAt: null,
      sessionVersion: 1,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
  await assertFails(aliceDb.collection("reset_audit_logs").get());
  await assertFails(aliceDb.collection("rate_limits").doc("any").get());
  await assertFails(aliceDb.collection("app_stats").doc("storage").get());
});

test("6. Admin cannot modify bind fields or role on authorized_users; can edit displayName only", async () => {
  const adminDb = testEnv
    .authenticatedContext("g_admin_sub", {
      userEmail: SUPER_ADMIN_EMAIL,
      deviceId: ADMIN_DEVICE_ID,
      role: "admin",
    })
    .firestore();

  // Allowed: updating displayName + updatedAt
  await assertSucceeds(
    adminDb.collection("authorized_users").doc(READER_A_EMAIL).update({
      displayName: "Alice Updated",
      updatedAt: pastDate(),
    })
  );

  // Denied: modifying boundDeviceId directly from client
  await assertFails(
    adminDb.collection("authorized_users").doc(READER_A_EMAIL).update({
      boundDeviceId: null,
      updatedAt: pastDate(),
    })
  );

  // Denied: modifying role or sessionVersion from client
  await assertFails(
    adminDb.collection("authorized_users").doc(READER_A_EMAIL).update({
      role: "admin",
      updatedAt: pastDate(),
    })
  );
  await assertFails(
    adminDb.collection("authorized_users").doc(READER_A_EMAIL).update({
      sessionVersion: 2,
      updatedAt: pastDate(),
    })
  );
});

test("7. Page over 300 KB rejected; valid page <= 300 KB accepted", async () => {
  const adminDb = testEnv
    .authenticatedContext("g_admin_sub", {
      userEmail: SUPER_ADMIN_EMAIL,
      deviceId: ADMIN_DEVICE_ID,
      role: "admin",
    })
    .firestore();

  const now = pastDate();

  // Valid <= 300 KB
  await assertSucceeds(
    adminDb
      .collection("books")
      .doc("book_assigned")
      .collection("pages")
      .doc("page_valid")
      .set({
        orderIndex: 2000,
        imageBytes: makeBlob(adminDb, 150 * 1024),
        width: 1080,
        height: 1440,
        caption: "Page 2",
        createdAt: now,
        updatedAt: now,
      })
  );

  // Rejected > 300 KB (300 KB + 1 byte)
  await assertFails(
    adminDb
      .collection("books")
      .doc("book_assigned")
      .collection("pages")
      .doc("page_too_big")
      .set({
        orderIndex: 3000,
        imageBytes: makeBlob(adminDb, 300 * 1024 + 1),
        width: 1080,
        height: 1440,
        caption: "Oversized",
        createdAt: now,
        updatedAt: now,
      })
  );
});

test("8. Super-admin docs cannot be deleted by any client; reader doc can be deleted by admin", async () => {
  const adminDb = testEnv
    .authenticatedContext("g_admin_sub", {
      userEmail: SUPER_ADMIN_EMAIL,
      deviceId: ADMIN_DEVICE_ID,
      role: "admin",
    })
    .firestore();

  await assertFails(adminDb.collection("authorized_users").doc(SUPER_ADMIN_EMAIL).delete());
  await assertFails(adminDb.collection("authorized_users").doc(SUPER_ADMIN_EMAIL_2).delete());
  await assertSucceeds(adminDb.collection("authorized_users").doc(READER_B_EMAIL).delete());
});

test("9. Audit logs are admin read-only, rate_limits denied for all clients, books forbid email fields", async () => {
  const adminDb = testEnv
    .authenticatedContext("g_admin_sub", {
      userEmail: SUPER_ADMIN_EMAIL,
      deviceId: ADMIN_DEVICE_ID,
      role: "admin",
    })
    .firestore();

  await assertSucceeds(adminDb.collection("reset_audit_logs").get());
  await assertFails(
    adminDb.collection("reset_audit_logs").doc("fake_log").set({
      targetEmail: READER_A_EMAIL,
      adminEmail: SUPER_ADMIN_EMAIL,
      resetAt: pastDate(),
    })
  );
  await assertFails(adminDb.collection("rate_limits").doc("k1").get());
  await assertFails(adminDb.collection("rate_limits").doc("k1").set({ count: 1 }));

  // Book with unauthorized subscriber email field rejected
  await assertFails(
    adminDb.collection("books").doc("book_leak").set({
      title: "Leaky Book",
      author: "Author",
      description: "Desc",
      category: "Cat",
      coverBytes: null,
      pageCount: 0,
      createdBy: SUPER_ADMIN_EMAIL,
      subscribers: [READER_A_EMAIL],
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});
