/* METADATA
{
  "name": "rem_android_stt_a1e",
  "display_name": {"en": "Rem Android STT A1E", "zh": "Rem Android STT A1E"},
  "description": {
    "en": "Diagnostic Russian system SpeechRecognizer test with exact callback timeline and native READY toast.",
    "zh": "Android system STT diagnostic."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "start_russian_system_stt_a1e",
      "description": {
        "en": "Start A1E Russian system STT. Wait for the native toast 'STT READY — говорите', then immediately speak one short Russian phrase.",
        "zh": "Start A1E Russian system STT."
      },
      "parameters": [
        {
          "name": "watchdog_ms",
          "type": "number",
          "required": false,
          "description": {"en": "Diagnostic watchdog only; default 30000 ms.", "zh": "Watchdog"}
        }
      ]
    },
    {
      "name": "read_russian_system_stt_a1e",
      "description": {
        "en": "Read A1E state, result/error, exact callback timeline, recognizer service, and timing deltas.",
        "zh": "Read A1E diagnostic state."
      },
      "parameters": []
    },
    {
      "name": "cancel_russian_system_stt_a1e",
      "description": {"en": "Cancel an active A1E recognition.", "zh": "Cancel."},
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
    "android_stt_a1e_helper",
    "rem-android-stt-a1e-helper.dex",
    true
  );
  if (!path) throw new Error("Helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.a1e."]});
  return path;
}

function parseStatus(raw) {
  try { return JSON.parse(String(raw)); }
  catch (_) { return {state: "PARSE_ERROR", raw: String(raw)}; }
}

async function start_russian_system_stt_a1e(params) {
  try {
    const helperPath = await ensureLoaded();
    const context = Java.getApplicationContext();
    const watchdogMs = Math.max(
      10000,
      Math.min(45000, Number(params && params.watchdog_ms) || 30000)
    );

    const accepted = Java.callStatic(
      "com.rem.stt.a1e.AndroidSttA1eHelper",
      "startRussianSystemRecognizer",
      context,
      watchdogMs
    );

    return {
      ok: !!accepted,
      probe: "W04-A1E",
      helperPath,
      watchdogMs,
      instruction: "Do not speak on this ToolPkg result. Wait for the native toast 'STT READY — говорите', then immediately speak one short Russian phrase. After 2–3 seconds call read_russian_system_stt_a1e."
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1E", error: describeError(error)};
  }
}

async function read_russian_system_stt_a1e() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const status = parseStatus(Java.callStatic(
      "com.rem.stt.a1e.AndroidSttA1eHelper",
      "getStatusJson",
      context
    ));
    return {
      ok: status.state === "RESULT",
      probe: "W04-A1E",
      ...status
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1E", error: describeError(error)};
  }
}

async function cancel_russian_system_stt_a1e() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    Java.callStatic(
      "com.rem.stt.a1e.AndroidSttA1eHelper",
      "cancel",
      context
    );
    return {ok: true, probe: "W04-A1E", cancelled: true};
  } catch (error) {
    return {ok: false, probe: "W04-A1E", error: describeError(error)};
  }
}

exports.start_russian_system_stt_a1e = start_russian_system_stt_a1e;
exports.read_russian_system_stt_a1e = read_russian_system_stt_a1e;
exports.cancel_russian_system_stt_a1e = cancel_russian_system_stt_a1e;
