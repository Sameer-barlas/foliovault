"use strict";

const { test, describe } = require("node:test");
const assert = require("node:assert/strict");
const { createApp } = require("../src/app");
const { createGoogleAuthService } = require("../src/services/googleAuthService");

const WEB_CLIENT_ID = "test-web-client.apps.googleusercontent.com";
const SUPER_ADMIN_EMAIL = "muhamad517uet@gmail.com";

function createInMemoryFirestore() {
  const store = new Map();
  let autoIdCounter = 1;

  function makeDocRef(colName, docId) {
    const key = `${colName}/${docId}`;
    return {
      id: docId,
      path: key,
      async get() {
        const val = store.get(key);
        return {
          exists: val !== undefined,
          id: docId,
          data: () => (val === undefined ? undefined : structuredClone(val)),
        };
      },
      async set(data, opts = {}) {
        if (opts.merge && store.has(key)) {
          store.set(key, { ...store.get(key), ...structuredClone(data) });
        } else {
          store.set(key, structuredClone(data));
        }
      },
      async update(data) {
        if (!store.has(key)) {
          throw new Error(`Document ${key} does not exist`);
        }
        store.set(key, { ...store.get(key), ...structuredClone(data) });
      },
    };
  }

  return {
    _store: store,
    collection(colName) {
      return {
        doc(docId) {
          const resolvedId = docId || `auto_${autoIdCounter++}`;
          return makeDocRef(colName, resolvedId);
        },
      };
    },
    async runTransaction(fn) {
      const staged = [];
      const tx = {
        async get(ref) {
          return ref.get();
        },
        set(ref, data, opts = {}) {
          staged.push({ op: "set", ref, data: structuredClone(data), opts });
        },
        update(ref, data) {
          staged.push({ op: "update", ref, data: structuredClone(data) });
        },
      };
      const result = await fn(tx);
      for (const item of staged) {
        if (item.op === "set") {
          await item.ref.set(item.data, item.opts);
        } else {
          await item.ref.update(item.data);
        }
      }
      return result;
    },
  };
}

function createHarness({ tokensByIdToken = {}, firebaseTokens = {}, nowMs = 1_700_000_000_000 } = {}) {
  const db = createInMemoryFirestore();
  const mintedTokens = [];
  let currentNow = nowMs;

  const mockOAuthClient = {
    async verifyIdToken({ idToken, audience }) {
      const record = tokensByIdToken[idToken];
      if (!record) {
        throw new Error("Invalid token signature");
      }
      if (record.aud !== audience) {
        throw new Error("Wrong audience");
      }
      return {
        getPayload: () => record,
      };
    },
  };

  const googleAuthService = createGoogleAuthService({
    googleWebClientId: WEB_CLIENT_ID,
    oauthClient: mockOAuthClient,
  });

  const mockAuth = {
    async createCustomToken(uid, claims) {
      mintedTokens.push({ uid, claims: structuredClone(claims) });
      return `custom_token_for_${uid}_${mintedTokens.length}`;
    },
    async verifyIdToken(token) {
      const decoded = firebaseTokens[token];
      if (!decoded) {
        throw new Error("Invalid Firebase ID token");
      }
      return decoded;
    },
  };

  const mockAppCheck = {
    async verifyToken(token) {
      if (token === "bad-appcheck") {
        throw new Error("Invalid App Check token");
      }
      return { appId: "1:test:android:app" };
    },
  };

  const silentLogger = { warn() {}, error() {}, info() {} };

  const app = createApp({
    env: {
      googleWebClientId: WEB_CLIENT_ID,
      superAdminEmail: SUPER_ADMIN_EMAIL,
      appCheckEnforce: false,
    },
    firebase: {
      db,
      auth: mockAuth,
      appCheck: mockAppCheck,
      FieldValue: { serverTimestamp: () => new Date(currentNow) },
    },
    googleAuthService,
    nowProvider: () => currentNow,
    serverTimestamp: () => new Date(currentNow),
    logger: silentLogger,
  });

  async function withServer(fn) {
    const server = await new Promise((resolve) => {
      const s = app.listen(0, "127.0.0.1", () => resolve(s));
    });
    const { port } = server.address();
    const baseUrl = `http://127.0.0.1:${port}`;
    try {
      return await fn(baseUrl);
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  }

  return {
    db,
    mintedTokens,
    setNow: (ms) => {
      currentNow = ms;
    },
    withServer,
  };
}

async function postJson(baseUrl, path, body, headers = {}) {
  const res = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
  const json = await res.json();
  return { status: res.status, json };
}

describe("FolioVault Express Backend", () => {
  test("GET /api/health returns { ok: true }", async () => {
    const h = createHarness();
    await h.withServer(async (baseUrl) => {
      const res = await fetch(`${baseUrl}/api/health`);
      assert.equal(res.status, 200);
      assert.deepEqual(await res.json(), { ok: true });
    });
  });

  test("POST /api/bind-device rejects invalid token and wrong audience", async () => {
    const h = createHarness({
      tokensByIdToken: {
        "valid_but_wrong_aud_token_12345": {
          aud: "other-client-id.apps.googleusercontent.com",
          email: "alice@example.com",
          email_verified: true,
          sub: "sub_alice",
        },
      },
    });

    await h.withServer(async (baseUrl) => {
      const r1 = await postJson(baseUrl, "/api/bind-device", {
        idToken: "completely_invalid_token_12345",
        deviceId: "dev-1111",
        deviceLabel: "Pixel 8",
      });
      assert.equal(r1.status, 401);
      assert.equal(r1.json.error, "INVALID_TOKEN");

      const r2 = await postJson(baseUrl, "/api/bind-device", {
        idToken: "valid_but_wrong_aud_token_12345",
        deviceId: "dev-1111",
        deviceLabel: "Pixel 8",
      });
      assert.equal(r2.status, 401);
      assert.equal(r2.json.error, "INVALID_TOKEN");
    });
  });

  test("POST /api/bind-device rejects unlisted Gmail with 403 NOT_AUTHORIZED", async () => {
    const h = createHarness({
      tokensByIdToken: {
        "stranger_token_123456789012345": {
          aud: WEB_CLIENT_ID,
          email: "Stranger@Example.com",
          email_verified: true,
          sub: "sub_stranger",
        },
      },
    });

    await h.withServer(async (baseUrl) => {
      const res = await postJson(baseUrl, "/api/bind-device", {
        idToken: "stranger_token_123456789012345",
        deviceId: "dev-stranger-1",
        deviceLabel: "Pixel 8",
      });
      assert.equal(res.status, 403);
      assert.equal(res.json.error, "NOT_AUTHORIZED");
      assert.equal(res.json.email, "stranger@example.com");
    });
  });

  test("POST /api/bind-device handles first bind, same device, and different device (409 DEVICE_LOCKED)", async () => {
    const h = createHarness({
      tokensByIdToken: {
        "alice_token_123456789012345678": {
          aud: WEB_CLIENT_ID,
          email: "Alice@Example.com",
          email_verified: true,
          sub: "sub_alice",
          name: "Alice Reader",
        },
      },
    });

    await h.db.collection("authorized_users").doc("alice@example.com").set({
      email: "alice@example.com",
      displayName: "Alice",
      role: "reader",
      boundDeviceId: null,
      boundDeviceLabel: null,
      boundUid: null,
      boundAt: null,
      sessionVersion: 1,
    });

    await h.withServer(async (baseUrl) => {
      // 1. First bind succeeds and mints exact claims
      const r1 = await postJson(baseUrl, "/api/bind-device", {
        idToken: "alice_token_123456789012345678",
        deviceId: "dev-pixel8-aaa",
        deviceLabel: "Pixel 8",
      });
      assert.equal(r1.status, 200);
      assert.equal(r1.json.role, "reader");
      assert.ok(r1.json.customToken);
      assert.equal(h.mintedTokens.length, 1);
      assert.equal(h.mintedTokens[0].uid, "g_sub_alice");
      assert.deepEqual(Object.keys(h.mintedTokens[0].claims).sort(), [
        "deviceId",
        "role",
        "userEmail",
      ]);
      assert.deepEqual(h.mintedTokens[0].claims, {
        deviceId: "dev-pixel8-aaa",
        userEmail: "alice@example.com",
        role: "reader",
      });

      // 2. Same device succeeds with fresh token
      const r2 = await postJson(baseUrl, "/api/bind-device", {
        idToken: "alice_token_123456789012345678",
        deviceId: "dev-pixel8-aaa",
        deviceLabel: "Pixel 8",
      });
      assert.equal(r2.status, 200);
      assert.equal(h.mintedTokens.length, 2);

      // 3. Different device returns 409 DEVICE_LOCKED with only boundDeviceLabel (no boundDeviceId leaked)
      const r3 = await postJson(baseUrl, "/api/bind-device", {
        idToken: "alice_token_123456789012345678",
        deviceId: "dev-other-bbb",
        deviceLabel: "Galaxy S24",
      });
      assert.equal(r3.status, 409);
      assert.equal(r3.json.error, "DEVICE_LOCKED");
      assert.equal(r3.json.boundDeviceLabel, "Pixel 8");
      assert.equal(r3.json.boundDeviceId, undefined);
    });
  });

  test("POST /api/bind-device bootstraps super-admin on first login", async () => {
    const h = createHarness({
      tokensByIdToken: {
        "super_admin_token_123456789012": {
          aud: WEB_CLIENT_ID,
          email: SUPER_ADMIN_EMAIL.toUpperCase(),
          email_verified: true,
          sub: "sub_super",
          name: "Muhamad",
        },
      },
    });

    await h.withServer(async (baseUrl) => {
      const res = await postJson(baseUrl, "/api/bind-device", {
        idToken: "super_admin_token_123456789012",
        deviceId: "dev-admin-01",
        deviceLabel: "Pixel 9 Pro",
      });
      assert.equal(res.status, 200);
      assert.equal(res.json.role, "admin");
      assert.deepEqual(h.mintedTokens[0].claims, {
        deviceId: "dev-admin-01",
        userEmail: SUPER_ADMIN_EMAIL,
        role: "admin",
      });

      const saved = (await h.db.collection("authorized_users").doc(SUPER_ADMIN_EMAIL).get()).data();
      assert.equal(saved.role, "admin");
      assert.equal(saved.boundDeviceId, "dev-admin-01");
      assert.equal(saved.sessionVersion, 1);
    });
  });

  test("POST /api/reset-device rejects non-admin, enforces super-admin protection, writes audit log, and enforces per-target & per-admin rate limits", async () => {
    const h = createHarness({
      firebaseTokens: {
        reader_id_token: {
          uid: "g_alice",
          userEmail: "alice@example.com",
          deviceId: "dev-alice",
          role: "reader",
        },
        admin_id_token: {
          uid: "g_super",
          userEmail: SUPER_ADMIN_EMAIL,
          deviceId: "dev-admin",
          role: "admin",
        },
        second_admin_token: {
          uid: "g_admin2",
          userEmail: "co-admin@example.com",
          deviceId: "dev-admin2",
          role: "admin",
        },
      },
    });

    await h.db.collection("authorized_users").doc(SUPER_ADMIN_EMAIL).set({
      email: SUPER_ADMIN_EMAIL,
      role: "admin",
      boundDeviceId: "dev-admin",
      boundDeviceLabel: "Pixel 9",
      sessionVersion: 1,
    });
    await h.db.collection("authorized_users").doc("co-admin@example.com").set({
      email: "co-admin@example.com",
      role: "admin",
      boundDeviceId: "dev-admin2",
      boundDeviceLabel: "Pixel 8a",
      sessionVersion: 1,
    });
    await h.db.collection("authorized_users").doc("alice@example.com").set({
      email: "alice@example.com",
      role: "reader",
      boundDeviceId: "dev-alice",
      boundDeviceLabel: "Pixel 8",
      boundUid: "g_alice",
      sessionVersion: 1,
    });

    await h.withServer(async (baseUrl) => {
      // Non-admin rejected
      const rNonAdmin = await postJson(
        baseUrl,
        "/api/reset-device",
        { targetEmail: "alice@example.com", reason: "self reset" },
        { Authorization: "Bearer reader_id_token" }
      );
      assert.equal(rNonAdmin.status, 403);

      // Co-admin cannot reset super-admin
      const rProtectSuper = await postJson(
        baseUrl,
        "/api/reset-device",
        { targetEmail: SUPER_ADMIN_EMAIL, reason: "attempt" },
        { Authorization: "Bearer second_admin_token" }
      );
      assert.equal(rProtectSuper.status, 403);

      // Valid reset #1 succeeds, clears binding, increments sessionVersion, writes audit log
      const r1 = await postJson(
        baseUrl,
        "/api/reset-device",
        { targetEmail: "alice@example.com", reason: "Lost phone" },
        { Authorization: "Bearer admin_id_token" }
      );
      assert.equal(r1.status, 200);
      assert.equal(r1.json.ok, true);
      assert.equal(r1.json.sessionVersion, 2);

      const aliceDoc = (await h.db.collection("authorized_users").doc("alice@example.com").get()).data();
      assert.equal(aliceDoc.boundDeviceId, null);
      assert.equal(aliceDoc.boundDeviceLabel, null);
      assert.equal(aliceDoc.sessionVersion, 2);

      const auditEntries = [...h.db._store.entries()].filter(([k]) =>
        k.startsWith("reset_audit_logs/")
      );
      assert.equal(auditEntries.length, 1);
      assert.equal(auditEntries[0][1].targetEmail, "alice@example.com");
      assert.equal(auditEntries[0][1].previousDeviceId, "dev-alice");
      assert.equal(auditEntries[0][1].previousDeviceLabel, "Pixel 8");
      assert.equal(auditEntries[0][1].adminEmail, SUPER_ADMIN_EMAIL);
      assert.equal(auditEntries[0][1].reason, "Lost phone");

      // Resets #2 and #3 for same target succeed; #4 within 24h returns 429 RATE_LIMITED
      assert.equal(
        (
          await postJson(
            baseUrl,
            "/api/reset-device",
            { targetEmail: "alice@example.com", reason: "Reset 2" },
            { Authorization: "Bearer admin_id_token" }
          )
        ).status,
        200
      );
      assert.equal(
        (
          await postJson(
            baseUrl,
            "/api/reset-device",
            { targetEmail: "alice@example.com", reason: "Reset 3" },
            { Authorization: "Bearer admin_id_token" }
          )
        ).status,
        200
      );
      const rTargetLimited = await postJson(
        baseUrl,
        "/api/reset-device",
        { targetEmail: "alice@example.com", reason: "Reset 4" },
        { Authorization: "Bearer admin_id_token" }
      );
      assert.equal(rTargetLimited.status, 429);
      assert.equal(rTargetLimited.json.error, "RATE_LIMITED");
      assert.ok(rTargetLimited.json.retryAfterSeconds > 0);

      // Per-admin rate limit: admin already used 3 resets in this hour; do 17 more across other users -> 21st returns 429
      for (let i = 1; i <= 18; i++) {
        const email = `user${i}@example.com`;
        await h.db.collection("authorized_users").doc(email).set({
          email,
          role: "reader",
          boundDeviceId: `dev-${i}`,
          boundDeviceLabel: `Phone ${i}`,
          sessionVersion: 1,
        });
      }
      for (let i = 1; i <= 17; i++) {
        const res = await postJson(
          baseUrl,
          "/api/reset-device",
          { targetEmail: `user${i}@example.com`, reason: "Bulk reset" },
          { Authorization: "Bearer admin_id_token" }
        );
        assert.equal(res.status, 200, `Expected reset #${i + 3} to succeed`);
      }
      const rAdminLimited = await postJson(
        baseUrl,
        "/api/reset-device",
        { targetEmail: "user18@example.com", reason: "21st reset" },
        { Authorization: "Bearer admin_id_token" }
      );
      assert.equal(rAdminLimited.status, 429);
      assert.equal(rAdminLimited.json.error, "RATE_LIMITED");
      assert.ok(rAdminLimited.json.retryAfterSeconds > 0);
    });
  });
});
