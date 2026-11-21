"use strict";

const { createApp } = require("../src/app");

let cachedApp = null;

module.exports = (req, res) => {
  if (!cachedApp) {
    cachedApp = createApp();
  }
  return cachedApp(req, res);
};
