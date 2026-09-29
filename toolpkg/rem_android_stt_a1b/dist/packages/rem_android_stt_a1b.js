/* METADATA
{
  "name": "rem_android_stt_a1b",
  "display_name": {"en": "Rem Android STT A1B", "zh": "Rem Android STT A1B"},
  "description": {
    "en": "Non-blocking Russian on-device Android STT. Start returns immediately; a separate tool reads result/status.",
    "zh": "Non-blocking Android STT A1B."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "start_russian_ondevice_stt_a1b",
      "description": {
        "en": "Start Russian on-device Android STT without system UI and return immediately. Wait about one second, then speak one phrase.",
        "zh": "Start Russian Android STT and return immediately."
      },
      "parameters": [
        {
          "name": "timeout_ms",
          "type": "number",
          "required": false,
          "description": {"en": "Timeout, default 20000 ms.", "zh": "Timeout"}
        }
      ]
    },
    {
      "name": "read_russian_ondevice_stt_a1b",
      "description": {
        "en": "Read current STT status/result. RESULT with text means success.",
        "zh": "Read Android STT result."
      },
      "parameters": []
    },
    {
      "name": "cancel_russian_ondevice_stt_a1b",
      "description": {"en": "Cancel active A1B recognition.", "zh": "Cancel."},
      "parameters": []
    }
  ]
}
*/

function describeError(error) {
  try { if (error && error.message) return String(error.message); } catch (_) {}
  try { return String(error); } catch (_) { return "unknown error"; }
}

async function ensureLoaded() {
  const path = await ToolPkg.readResource(
    "android_stt_a1b_helper",
    "rem-android-stt-a1b-helper.dex",
    true
  );
  if (!path) throw new Error("Helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.a1b."]});
  return path;
}

function parseStatus(raw) {
  try { return JSON.parse(String(raw)); }
  catch (_) { return {state: "PARSE_ERROR", raw: String(raw)}; }
}

async function start_russian_ondevice_stt_a1b(params) {
  try {
    const helperPath = await ensureLoaded();
    const context = Java.getApplicationContext();
    const timeoutMs = Math.max(5000, Math.min(30000, Number(params && params.timeout_ms) || 20000));
    const accepted = Java.callStatic(
      "com.rem.stt.a1b.AndroidSttA1bHelper",
      "startRussianMicTest",
      context,
      timeoutMs
    );
    const status = parseStatus(Java.callStatic(
      "com.rem.stt.a1b.AndroidSttA1bHelper",
      "getStatusJson",
      context
    ));
    return {
      ok: !!accepted,
      probe: "W04-A1B",
      helperPath,
      instruction: "Wait about 1 second, speak one short Russian phrase, then call read_russian_ondevice_stt_a1b.",
      status
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1B", error: describeError(error)};
  }
}

async function read_russian_ondevice_stt_a1b() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const status = parseStatus(Java.callStatic(
      "com.rem.stt.a1b.AndroidSttA1bHelper",
      "getStatusJson",
      context
    ));
    return {
      ok: status.state === "RESULT",
      probe: "W04-A1B",
      ...status
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1B", error: describeError(error)};
  }
}

async function cancel_russian_ondevice_stt_a1b() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    Java.callStatic("com.rem.stt.a1b.AndroidSttA1bHelper", "cancel", context);
    return {ok: true, cancelled: true};
  } catch (error) {
    return {ok: false, error: describeError(error)};
  }
}

exports.start_russian_ondevice_stt_a1b = start_russian_ondevice_stt_a1b;
exports.read_russian_ondevice_stt_a1b = read_russian_ondevice_stt_a1b;
exports.cancel_russian_ondevice_stt_a1b = cancel_russian_ondevice_stt_a1b;
