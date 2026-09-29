/* METADATA
{
  "name": "rem_android_stt_a1d",
  "display_name": {"en": "Rem Android STT A1D", "zh": "Rem Android STT A1D"},
  "description": {
    "en": "Non-blocking Russian recognition through Android's default system SpeechRecognizer, no system UI.",
    "zh": "Android default system STT."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "start_russian_system_stt_a1d",
      "description": {
        "en": "Start Russian recognition through Android's default system SpeechRecognizer and return immediately. Speak one short phrase after about one second.",
        "zh": "Start Russian system STT."
      },
      "parameters": [
        {
          "name": "timeout_ms",
          "type": "number",
          "required": false,
          "description": {"en": "Timeout in milliseconds, default 20000.", "zh": "Timeout"}
        }
      ]
    },
    {
      "name": "read_russian_system_stt_a1d",
      "description": {
        "en": "Read the current system STT status/result. RESULT with text means success.",
        "zh": "Read system STT result."
      },
      "parameters": []
    },
    {
      "name": "cancel_russian_system_stt_a1d",
      "description": {"en": "Cancel an active A1D recognition.", "zh": "Cancel."},
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
    "android_stt_a1d_helper",
    "rem-android-stt-a1d-helper.dex",
    true
  );
  if (!path) throw new Error("Helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.a1d."]});
  return path;
}

function parseStatus(raw) {
  try { return JSON.parse(String(raw)); }
  catch (_) { return {state: "PARSE_ERROR", raw: String(raw)}; }
}

async function start_russian_system_stt_a1d(params) {
  try {
    const helperPath = await ensureLoaded();
    const context = Java.getApplicationContext();
    const timeoutMs = Math.max(
      5000,
      Math.min(30000, Number(params && params.timeout_ms) || 20000)
    );

    const accepted = Java.callStatic(
      "com.rem.stt.a1d.AndroidSttA1dHelper",
      "startRussianSystemRecognizer",
      context,
      timeoutMs
    );

    return {
      ok: !!accepted,
      probe: "W04-A1D",
      helperPath,
      instruction: "Wait about one second, speak one short Russian phrase, then call read_russian_system_stt_a1d."
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1D", error: describeError(error)};
  }
}

async function read_russian_system_stt_a1d() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const status = parseStatus(Java.callStatic(
      "com.rem.stt.a1d.AndroidSttA1dHelper",
      "getStatusJson",
      context
    ));
    return {
      ok: status.state === "RESULT",
      probe: "W04-A1D",
      ...status
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1D", error: describeError(error)};
  }
}

async function cancel_russian_system_stt_a1d() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    Java.callStatic(
      "com.rem.stt.a1d.AndroidSttA1dHelper",
      "cancel",
      context
    );
    return {ok: true, cancelled: true};
  } catch (error) {
    return {ok: false, error: describeError(error)};
  }
}

exports.start_russian_system_stt_a1d = start_russian_system_stt_a1d;
exports.read_russian_system_stt_a1d = read_russian_system_stt_a1d;
exports.cancel_russian_system_stt_a1d = cancel_russian_system_stt_a1d;
