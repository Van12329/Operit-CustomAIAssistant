/* METADATA
{
  "name": "rem_android_stt_bridge_tools",
  "display_name": {"en": "Rem Voice Session Bridge", "zh": "Rem Voice Session Bridge"},
  "description": {
    "en": "Status and control tools for the wake-launched Russian Android SpeechRecognizer bridge.",
    "zh": "Russian Android STT bridge controls."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "android_stt_bridge_status",
      "description": {
        "en": "Read the current Android STT wake bridge status and last recognition result.",
        "zh": "Read Android STT bridge status."
      },
      "parameters": []
    },
    {
      "name": "android_stt_bridge_set_enabled",
      "description": {
        "en": "Enable or disable the Android STT wake bridge without uninstalling the ToolPkg.",
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
      bridge: "W07-VOICE-SESSION",
      ...parseStatus(raw)
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W07-VOICE-SESSION",
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
      bridge: "W07-VOICE-SESSION",
      enabled
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W07-VOICE-SESSION",
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
      bridge: "W07-VOICE-SESSION",
      avatarBallEnabled: enabled
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W07-VOICE-SESSION",
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
      bridge: "W07-VOICE-SESSION",
      instruction: "If the current floating session was launched by WakeUpWord, the bridge will attach automatically."
    };
  } catch (error) {
    return {
      ok: false,
      bridge: "W07-VOICE-SESSION",
      error: String(error && error.message ? error.message : error)
    };
  }
}

exports.android_stt_bridge_status = android_stt_bridge_status;
exports.android_stt_bridge_set_enabled = android_stt_bridge_set_enabled;
exports.android_stt_bridge_set_avatar_ball = android_stt_bridge_set_avatar_ball;
exports.android_stt_bridge_probe = android_stt_bridge_probe;
