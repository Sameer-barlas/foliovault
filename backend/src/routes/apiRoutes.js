"use strict";

const express = require("express");
const { healthCheck } = require("../controllers/healthController");

function createApiRouter({ deviceController, verifyAppCheck, requireFirebaseAuth }) {
  const router = express.Router();

  router.get("/health", healthCheck);
  router.post("/bind-device", verifyAppCheck, deviceController.bindDevice);
  router.post("/reset-device", verifyAppCheck, requireFirebaseAuth, deviceController.resetDevice);

  return router;
}

module.exports = { createApiRouter };
