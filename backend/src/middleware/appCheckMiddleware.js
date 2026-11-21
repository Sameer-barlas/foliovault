"use strict";

function createAppCheckMiddleware({ appCheck, enforce = false, logger = console }) {
  return async function verifyAppCheck(req, res, next) {
    const token = req.header("X-Firebase-AppCheck");
    if (!token) {
      if (enforce) {
        return res.status(401).json({
          error: "APPCHECK_REQUIRED",
          message: "Missing X-Firebase-AppCheck header.",
        });
      }
      logger.warn("[AppCheck] Monitor mode: request arrived without X-Firebase-AppCheck header.");
      return next();
    }

    try {
      await appCheck.verifyToken(token);
      return next();
    } catch (_err) {
      if (enforce) {
        return res.status(401).json({
          error: "APPCHECK_INVALID",
          message: "Invalid Firebase App Check token.",
        });
      }
      logger.warn("[AppCheck] Monitor mode: invalid App Check token observed.");
      return next();
    }
  };
}

module.exports = { createAppCheckMiddleware };
