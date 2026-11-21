"use strict";

function createDeviceController({ deviceService }) {
  async function bindDevice(req, res, next) {
    try {
      const { idToken, deviceId, deviceLabel } = req.body || {};
      const result = await deviceService.bindDevice({
        idToken,
        deviceId,
        deviceLabel,
      });
      return res.status(200).json(result);
    } catch (err) {
      return next(err);
    }
  }

  async function resetDevice(req, res, next) {
    try {
      const { targetEmail, reason } = req.body || {};
      const result = await deviceService.resetDevice({
        callerToken: req.callerToken,
        targetEmail,
        reason,
      });
      return res.status(200).json(result);
    } catch (err) {
      return next(err);
    }
  }

  return {
    bindDevice,
    resetDevice,
  };
}

module.exports = { createDeviceController };
