"use strict";

const { loadEnv } = require("./src/config/env");
const { createApp } = require("./src/app");

const env = loadEnv();
const app = createApp({ env });

app.listen(env.port, () => {
  console.log(`Redoo backend listening on port ${env.port}`);
});
