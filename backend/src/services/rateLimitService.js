"use strict";

const { ApiError } = require("./errors");

const TARGET_MAX_RESETS = 3;
const TARGET_WINDOW_MS = 24 * 60 * 60 * 1000; // 24 hours
const ADMIN_MAX_RESETS = 20;
const ADMIN_WINDOW_MS = 60 * 60 * 1000; // 1 hour

function sanitizeKeyPart(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9._@-]/g, "_");
}

function createRateLimitService({ db, nowProvider = () => Date.now() }) {
  async function checkAndConsumeWindow({ key, maxCount, windowMs, scopeLabel }) {
    const ref = db.collection("rate_limits").doc(key);
    const nowMs = nowProvider();

    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const data = snap.exists ? snap.data() : null;
      const existingTimestamps = Array.isArray(data?.timestamps) ? data.timestamps : [];
      const cutoff = nowMs - windowMs;
      const active = existingTimestamps.filter((ts) => typeof ts === "number" && ts > cutoff);

      if (active.length >= maxCount) {
        const oldestActive = Math.min(...active);
        const retryAtMs = oldestActive + windowMs;
        const retryAfterSeconds = Math.max(1, Math.ceil((retryAtMs - nowMs) / 1000));
        throw new ApiError(
          429,
          "RATE_LIMITED",
          `Rate limit exceeded for ${scopeLabel}. Try again later.`,
          {
            retryAfterSeconds,
            retryAt: new Date(retryAtMs).toISOString(),
          }
        );
      }

      active.push(nowMs);
      tx.set(
        ref,
        {
          timestamps: active,
          updatedAt: new Date(nowMs),
        },
        { merge: true }
      );
    });
  }

  async function enforceResetLimits({ targetEmail, adminEmail }) {
    await checkAndConsumeWindow({
      key: `reset_target__${sanitizeKeyPart(targetEmail)}`,
      maxCount: TARGET_MAX_RESETS,
      windowMs: TARGET_WINDOW_MS,
      scopeLabel: "target user (max 3 resets per 24h)",
    });

    await checkAndConsumeWindow({
      key: `reset_admin__${sanitizeKeyPart(adminEmail)}`,
      maxCount: ADMIN_MAX_RESETS,
      windowMs: ADMIN_WINDOW_MS,
      scopeLabel: "admin account (max 20 resets per hour)",
    });
  }

  return {
    enforceResetLimits,
    TARGET_MAX_RESETS,
    TARGET_WINDOW_MS,
    ADMIN_MAX_RESETS,
    ADMIN_WINDOW_MS,
  };
}

module.exports = {
  createRateLimitService,
  TARGET_MAX_RESETS,
  TARGET_WINDOW_MS,
  ADMIN_MAX_RESETS,
  ADMIN_WINDOW_MS,
};
