"use strict";

const { OAuth2Client } = require("google-auth-library");
const { ApiError } = require("./errors");

function createGoogleAuthService({ googleWebClientId, oauthClient = null }) {
  const client = oauthClient || new OAuth2Client(googleWebClientId);

  async function verifyGoogleIdToken(idToken) {
    if (typeof idToken !== "string" || idToken.trim().length < 20) {
      throw new ApiError(401, "INVALID_TOKEN", "Missing or malformed Google ID token.");
    }
    if (!googleWebClientId) {
      throw new ApiError(500, "SERVER_MISCONFIGURED", "GOOGLE_WEB_CLIENT_ID is not configured.");
    }

    let payload;
    try {
      const ticket = await client.verifyIdToken({
        idToken: idToken.trim(),
        audience: googleWebClientId,
      });
      payload = ticket.getPayload();
    } catch (_err) {
      throw new ApiError(401, "INVALID_TOKEN", "Google ID token verification failed.");
    }

    if (!payload || payload.aud !== googleWebClientId) {
      throw new ApiError(401, "INVALID_TOKEN", "Google ID token audience mismatch.");
    }
    if (payload.email_verified !== true || typeof payload.email !== "string" || !payload.email.trim()) {
      throw new ApiError(401, "INVALID_TOKEN", "Google account email is not verified.");
    }
    if (typeof payload.sub !== "string" || !payload.sub.trim()) {
      throw new ApiError(401, "INVALID_TOKEN", "Google account subject is missing.");
    }

    return {
      sub: payload.sub.trim(),
      email: payload.email.trim().toLowerCase(),
      displayName: typeof payload.name === "string" ? payload.name.trim().slice(0, 120) : "",
    };
  }

  return { verifyGoogleIdToken };
}

module.exports = { createGoogleAuthService };
