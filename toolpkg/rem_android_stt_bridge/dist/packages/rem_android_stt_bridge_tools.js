/* METADATA
{
  "name": "rem_android_stt_bridge_tools",
  "display_name": {"en": "Rem Voice Session Bridge", "zh": "Rem Voice Session Bridge"},
  "description": {
    "en": "Status and control tools for the global Android SpeechRecognizer bridge (wake + manual fullscreen, RU/ES).",
    "zh": "Russian Android STT bridge controls."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "android_stt_bridge_status",
      "description": {
        "en": "Read the current Android STT bridge status, session kind, language and last recognition result.",
        "zh": "Read Android STT bridge status."
      },
      "parameters": []
    },
    {
      "name": "android_stt_bridge_set_enabled",
      "description": {
        "en": "Enable or disable the Android STT bridge without uninstalling the ToolPkg.",
        "zh": "Enable or disable Android STT bridge."
      },
      "parameters": [
        {
          "name": "enabled",
          "type": "boolean",
          "required": true,
          "description": {"en": "true to enable, false to disable", "zh": "Enable state"}
        }
      ]
    },
    {
      "name": "android_stt_bridge_set_avatar_ball",
      "description": {
        "en": "Enable or disable the animated avatar-ball mode while keeping Russian Android STT active.",
        "zh": "Enable or disable animated avatar-ball mode."
      },
      "parameters": [
        {
          "name": "enabled",
          "type": "boolean",
          "required": true,
          "description": {"en": "true to enable avatar-ball mode, false to keep fullscreen", "zh": "Enable avatar-ball"}
        }
      ]
    },
    {
      "name": "android_stt_bridge_set_language",
      "description": {
        "en": "Set Android STT recognition language. RU uses ru-RU; ES uses es-AR.",
        "zh": "Set Android STT language."
      },
      "parameters": [
        {
          "name": "mode",
          "type": "string",
          "required": true,
          "description": {"en": "RU or ES", "zh": "RU or ES"}
        }
      ]
    },
    {
      "name": "android_stt_bridge_set_manual_override",
      "description": {
        "en": "Enable or disable Android STT takeover for ordinary manual fullscreen voice sessions.",
        "zh": "Enable or disable manual fullscreen STT override."
      },
      "parameters": [
        {
          "name": "enabled",
          "type": "boolean",
          "required": true,
          "description": {"en": "true to override ordinary manual voice sessions", "zh": "Enable manual override"}
        }
      ]
    },
    {
      "name": "android_stt_bridge_probe",
      "description": {
        "en": "Ask the bridge to probe whether a wake-launched floating voice session is already open and attach if possible.",
        "zh": "Probe current wake-launched floating session."
      },
      "parameters": []
    }
  ]
}
*/

let helperLoaded = false;

async function ensureLoaded() {
  if (helperLoaded) return true;

  const path = await ToolPkg.readResource(
    "android_stt_bridge_helper",
    "rem-android-stt-bridge-helper.dex",
    true
  );
  if (!path) throw new Error("Helper resource path is empty");

  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.bridge."]});
  helperLoaded = true;

  const context = Java.getApplicationContext();
  Java.callStatic(
    "com.rem.stt.bridge.AndroidSttBridgeHelper",
    "install",
    context
  );
  return true;
}

function parseStatus(raw) {
  try { return JSON.parse(String(raw)); }
  catch (_) { return {state: "PARSE_ERROR", raw: String(raw)}; }
}

async function android_stt_bridge_status() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const raw = Java.callStatic(
      "com.rem.stt.bridge.AndroidSttBridgeHelper",
      "getStatusJson",
      context
    );
    return {
      ok: true,
      bridge: "W08-GLOBAL-STT",
      ...parseStatus(raw)
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W08-GLOBAL-STT",
      error: String(error && error.message ? error.message : error)
    };
  }
}

async function android_stt_bridge_set_enabled(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const enabled = !!(params && params.enabled);

    const ok = Java.callStatic(
      "com.rem.stt.bridge.AndroidSttBridgeHelper",
      "setEnabled",
      context,
      enabled
    );

    return {
      ok: !!ok,
      bridge: "W08-GLOBAL-STT",
      enabled
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W08-GLOBAL-STT",
      error: String(error && error.message ? error.message : error)
    };
  }
}


async function android_stt_bridge_set_avatar_ball(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const enabled = !!(params && params.enabled);

    const ok = Java.callStatic(
      "com.rem.stt.bridge.AndroidSttBridgeHelper",
      "setAvatarBallEnabled",
      context,
      enabled
    );

    return {
      ok: !!ok,
      bridge: "W08-GLOBAL-STT",
      avatarBallEnabled: enabled
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W08-GLOBAL-STT",
      error: String(error && error.message ? error.message : error)
    };
  }
}

async function android_stt_bridge_set_language(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const mode = String(params && params.mode ? params.mode : "RU").toUpperCase();

    const ok = Java.callStatic(
      "com.rem.stt.bridge.AndroidSttBridgeHelper",
      "setLanguageMode",
      context,
      mode
    );

    return {
      ok: !!ok,
      bridge: "W08-GLOBAL-STT",
      requestedMode: mode
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W08-GLOBAL-STT",
      error: String(error && error.message ? error.message : error)
    };
  }
}

async function android_stt_bridge_set_manual_override(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const enabled = !!(params && params.enabled);

    const ok = Java.callStatic(
      "com.rem.stt.bridge.AndroidSttBridgeHelper",
      "setManualOverrideEnabled",
      context,
      enabled
    );

    return {
      ok: !!ok,
      bridge: "W08-GLOBAL-STT",
      manualOverrideEnabled: enabled
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W08-GLOBAL-STT",
      error: String(error && error.message ? error.message : error)
    };
  }
}

async function android_stt_bridge_probe() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();

    const ok = Java.callStatic(
      "com.rem.stt.bridge.AndroidSttBridgeHelper",
      "forceProbe",
      context
    );

    return {
      ok: !!ok,
      bridge: "W08-GLOBAL-STT",
      instruction: "The bridge will attach to a current WakeUpWord session or to an ordinary FULLSCREEN voice session when manual override is enabled."
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W08-GLOBAL-STT",
      error: String(error && error.message ? error.message : error)
    };
  }
}

exports.android_stt_bridge_status = android_stt_bridge_status;
exports.android_stt_bridge_set_enabled = android_stt_bridge_set_enabled;
exports.android_stt_bridge_set_avatar_ball = android_stt_bridge_set_avatar_ball;
exports.android_stt_bridge_set_language = android_stt_bridge_set_language;
exports.android_stt_bridge_set_manual_override = android_stt_bridge_set_manual_override;
exports.android_stt_bridge_probe = android_stt_bridge_probe;
