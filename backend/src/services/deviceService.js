"use strict";

const { ApiError } = require("./errors");

function createDeviceService({
  db,
  auth,
  googleAuthService,
  rateLimitService,
  superAdminEmail,
  superAdminEmails,
  serverTimestamp = () => new Date(),
}) {
  const rawList = Array.isArray(superAdminEmails)
    ? superAdminEmails
    : String(superAdminEmail || "")
        .split(",")
        .map((e) => e.trim().toLowerCase())
        .filter(Boolean);

  const adminSet = new Set(rawList);
  adminSet.add("muhamad517uet@gmail.com");
  adminSet.add("sayazsultan@gmail.com");

  function isSuperAdmin(email) {
    return adminSet.has(String(email || "").trim().toLowerCase());
  }

  async function bindDevice({ idToken, deviceId, deviceLabel }) {
    if (typeof deviceId !== "string" || deviceId.trim().length < 4 || deviceId.trim().length > 160) {
      throw new ApiError(400, "INVALID_DEVICE_ID", "A valid deviceId is required.");
    }
    const cleanDeviceId = deviceId.trim();
    const cleanDeviceLabel =
      typeof deviceLabel === "string" && deviceLabel.trim().length > 0
        ? deviceLabel.trim().slice(0, 120)
        : "Android Device";

    const googleUser = await googleAuthService.verifyGoogleIdToken(idToken);
    const email = googleUser.email;
    const uid = `g_${googleUser.sub}`;
    const userRef = db.collection("authorized_users").doc(email);

    const boundResult = await db.runTransaction(async (tx) => {
      const snap = await tx.get(userRef);
      const now = serverTimestamp();

      if (!snap.exists) {
        if (isSuperAdmin(email)) {
          tx.set(userRef, {
            email,
            displayName: googleUser.displayName || "Super Admin",
            role: "admin",
            boundDeviceId: cleanDeviceId,
            boundDeviceLabel: cleanDeviceLabel,
            boundUid: uid,
            boundAt: now,
            sessionVersion: 1,
            createdAt: now,
            updatedAt: now,
          });
          return { role: "admin" };
        }
        throw new ApiError(
          403,
          "NOT_AUTHORIZED",
          "This Google account is not authorized for Redoo.",
          { email }
        );
      }

      const data = snap.data() || {};
      const role = data.role === "admin" ? "admin" : "reader";

      if (!data.boundDeviceId) {
        tx.update(userRef, {
          boundDeviceId: cleanDeviceId,
          boundDeviceLabel: cleanDeviceLabel,
          boundUid: uid,
          boundAt: now,
          displayName: data.displayName || googleUser.displayName || "",
          updatedAt: now,
        });
        return { role };
      }

      if (data.boundDeviceId === cleanDeviceId) {
        tx.update(userRef, {
          boundDeviceLabel: cleanDeviceLabel,
          boundUid: uid,
          updatedAt: now,
        });
        return { role };
      }

      throw new ApiError(
        409,
        "DEVICE_LOCKED",
        "Account is already bound to another physical device.",
        {
          boundDeviceLabel: data.boundDeviceLabel || "Another device",
        }
      );
    });

    // Locked Decision #2: Custom token carries EXACTLY these three claims (deviceId, userEmail, role).
    const claims = {
      deviceId: cleanDeviceId,
      userEmail: email,
      role: boundResult.role,
    };

    const customToken = await auth.createCustomToken(uid, claims);
    return {
      customToken,
      role: boundResult.role,
      email,
    };
  }

  async function resetDevice({ callerToken, targetEmail, reason }) {
    const callerEmail =
      typeof callerToken?.userEmail === "string"
        ? callerToken.userEmail.trim().toLowerCase()
        : "";
    const callerDeviceId =
      typeof callerToken?.deviceId === "string" ? callerToken.deviceId.trim() : "";
    const callerUid = typeof callerToken?.uid === "string" ? callerToken.uid.trim() : "";

    if (!callerEmail || !callerDeviceId || !callerUid) {
      throw new ApiError(401, "UNAUTHORIZED", "Invalid authentication token claims.");
    }

    if (typeof targetEmail !== "string" || targetEmail.trim().length < 4) {
      throw new ApiError(400, "INVALID_TARGET_EMAIL", "A valid targetEmail is required.");
    }
    const cleanTargetEmail = targetEmail.trim().toLowerCase();
    const cleanReason =
      typeof reason === "string" ? reason.trim().slice(0, 300) : "";

    // Re-read caller doc to verify live admin role and bound device match
    const callerSnap = await db.collection("authorized_users").doc(callerEmail).get();
    if (!callerSnap.exists) {
      throw new ApiError(403, "FORBIDDEN", "Caller is not an authorized admin.");
    }
    const callerData = callerSnap.data() || {};
    if (callerData.role !== "admin" || callerData.boundDeviceId !== callerDeviceId) {
      throw new ApiError(403, "FORBIDDEN", "Admin role and bound device match required.");
    }

    // Only a super-admin may reset a super-admin
    if (isSuperAdmin(cleanTargetEmail) && !isSuperAdmin(callerEmail)) {
      throw new ApiError(403, "FORBIDDEN", "Only a super-admin may reset a super-admin device.");
    }

    // Check rate limits (3/target/24h, 20/admin/hr) before mutating target
    await rateLimitService.enforceResetLimits({
      targetEmail: cleanTargetEmail,
      adminEmail: callerEmail,
    });

    const targetRef = db.collection("authorized_users").doc(cleanTargetEmail);
    const auditRef = db.collection("reset_audit_logs").doc();

    const result = await db.runTransaction(async (tx) => {
      const targetSnap = await tx.get(targetRef);
      if (!targetSnap.exists) {
        throw new ApiError(404, "USER_NOT_FOUND", "Target user does not exist.");
      }

      const targetData = targetSnap.data() || {};
      const now = serverTimestamp();
      const nextSessionVersion =
        typeof targetData.sessionVersion === "number" ? targetData.sessionVersion + 1 : 2;

      tx.update(targetRef, {
        boundDeviceId: null,
        boundDeviceLabel: null,
        boundUid: null,
        boundAt: null,
        sessionVersion: nextSessionVersion,
        updatedAt: now,
      });

      tx.set(auditRef, {
        targetEmail: cleanTargetEmail,
        previousDeviceId: targetData.boundDeviceId || null,
        previousDeviceLabel: targetData.boundDeviceLabel || null,
        adminEmail: callerEmail,
        adminUid: callerUid,
        reason: cleanReason,
        resetAt: now,
      });

      return {
        targetEmail: cleanTargetEmail,
        previousDeviceLabel: targetData.boundDeviceLabel || null,
        sessionVersion: nextSessionVersion,
      };
    });

    return {
      ok: true,
      ...result,
    };
  }

  return {
    bindDevice,
    resetDevice,
  };
}

module.exports = { createDeviceService };
