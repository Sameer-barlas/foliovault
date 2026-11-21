"use strict";

const express = require("express");
const helmet = require("helmet");
const { loadEnv } = require("./config/env");
const { getFirebaseServices } = require("./config/firebase");
const { createGoogleAuthService } = require("./services/googleAuthService");
const { createRateLimitService } = require("./services/rateLimitService");
const { createDeviceService } = require("./services/deviceService");
const { createAppCheckMiddleware } = require("./middleware/appCheckMiddleware");
const { createAuthMiddleware } = require("./middleware/authMiddleware");
const { createDeviceController } = require("./controllers/deviceController");
const { createApiRouter } = require("./routes/apiRoutes");
const { ApiError } = require("./services/errors");

function createApp(overrides = {}) {
  const env = overrides.env || loadEnv();
  const firebase = overrides.firebase || getFirebaseServices(env);
  const googleAuthService =
    overrides.googleAuthService ||
    createGoogleAuthService({ googleWebClientId: env.googleWebClientId });
  const rateLimitService =
    overrides.rateLimitService ||
    createRateLimitService({
      db: firebase.db,
      nowProvider: overrides.nowProvider,
    });
  const deviceService =
    overrides.deviceService ||
    createDeviceService({
      db: firebase.db,
      auth: firebase.auth,
      googleAuthService,
      rateLimitService,
      superAdminEmail: env.superAdminEmail,
      superAdminEmails: env.superAdminEmails,
      serverTimestamp:
        overrides.serverTimestamp ||
        (() =>
          firebase.FieldValue && typeof firebase.FieldValue.serverTimestamp === "function"
            ? firebase.FieldValue.serverTimestamp()
            : new Date()),
    });

  const verifyAppCheck = createAppCheckMiddleware({
    appCheck: firebase.appCheck,
    enforce: env.appCheckEnforce,
    logger: overrides.logger || console,
  });
  const requireFirebaseAuth = createAuthMiddleware({ auth: firebase.auth });
  const deviceController = createDeviceController({ deviceService });
  const apiRouter = createApiRouter({
    deviceController,
    verifyAppCheck,
    requireFirebaseAuth,
  });

  const app = express();
  app.disable("x-powered-by");
  app.use(helmet());
  app.use(express.json({ limit: "32kb" }));

  app.use("/api", apiRouter);

  app.use((_req, res) => {
    res.status(404).json({ error: "NOT_FOUND", message: "Endpoint not found." });
  });

  // Centralized error handler — never logs or echoes tokens
  app.use((err, _req, res, _next) => {
    if (err instanceof ApiError) {
      return res.status(err.status).json({
        error: err.code,
        message: err.message,
        ...err.extra,
      });
    }
    if (err && err.type === "entity.parse.failed") {
      return res.status(400).json({
        error: "INVALID_JSON",
        message: "Malformed JSON request body.",
      });
    }
    return res.status(500).json({
      error: "INTERNAL_ERROR",
      message: "Unexpected server error.",
    });
  });

  return app;
}

module.exports = { createApp };
