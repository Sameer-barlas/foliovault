"use strict";

function createAuthMiddleware({ auth }) {
  return async function requireFirebaseAuth(req, res, next) {
    const header = req.header("Authorization") || "";
    if (!header.startsWith("Bearer ")) {
      return res.status(401).json({
        error: "UNAUTHORIZED",
        message: "Missing Authorization Bearer token.",
      });
    }

    const token = header.slice("Bearer ".length).trim();
    if (!token) {
      return res.status(401).json({
        error: "UNAUTHORIZED",
        message: "Empty Authorization Bearer token.",
      });
    }

    try {
      const decoded = await auth.verifyIdToken(token);
      req.callerToken = decoded;
      return next();
    } catch (_err) {
      return res.status(401).json({
        error: "UNAUTHORIZED",
        message: "Invalid or expired Firebase ID token.",
      });
    }
  };
}

module.exports = { createAuthMiddleware };
