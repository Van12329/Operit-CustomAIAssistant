/* METADATA
{
  "name": "rem_android_stt_a1f",
  "display_name": {"en": "Rem Android STT A1F", "zh": "Rem Android STT A1F"},
  "description": {
    "en": "Russian system STT test with explicit wake-listener suspend/release handoff.",
    "zh": "Android system STT wake handoff test."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "start_russian_system_stt_a1f",
      "description": {
        "en": "Temporarily suspend Operit wake listening, wait for microphone release, then start Russian system SpeechRecognizer. Speak only after the native READY toast.",
        "zh": "Start A1F wake handoff STT."
      },
      "parameters": [
        {
          "name": "watchdog_ms",
          "type": "number",
          "required": false,
          "description": {"en": "Diagnostic watchdog, default 30000 ms.", "zh": "Watchdog"}
        },
        {
          "name": "handoff_delay_ms",
          "type": "number",
          "required": false,
          "description": {"en": "Delay after wake suspend request before starting STT, default 700 ms.", "zh": "Handoff delay"}
        }
      ]
    },
    {
      "name": "read_russian_system_stt_a1f",
      "description": {
        "en": "Read A1F result/error, wake suspend/resume flags, and exact callback timeline.",
        "zh": "Read A1F state."
      },
      "parameters": []
    },
    {
      "name": "cancel_russian_system_stt_a1f",
      "description": {"en": "Cancel A1F and resume wake listening.", "zh": "Cancel."},
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
    "android_stt_a1f_helper",
    "rem-android-stt-a1f-helper.dex",
    true
  );
  if (!path) throw new Error("Helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.a1f."]});
  return path;
}

function parseStatus(raw) {
  try { return JSON.parse(String(raw)); }
  catch (_) { return {state: "PARSE_ERROR", raw: String(raw)}; }
}

async function start_russian_system_stt_a1f(params) {
  try {
    const helperPath = await ensureLoaded();
    const context = Java.getApplicationContext();

    const watchdogMs = Math.max(
      10000,
      Math.min(45000, Number(params && params.watchdog_ms) || 30000)
    );

    const handoffDelayMs = Math.max(
      250,
      Math.min(2000, Number(params && params.handoff_delay_ms) || 700)
    );

    const accepted = Java.callStatic(
      "com.rem.stt.a1f.AndroidSttA1fHelper",
      "startRussianSystemRecognizer",
      context,
      watchdogMs,
      handoffDelayMs
    );

    return {
      ok: !!accepted,
      probe: "W04-A1F",
      helperPath,
      watchdogMs,
      handoffDelayMs,
      instruction: "Keep Always Listening / WakeUpWord enabled. Wait for the native toast 'WAKE PAUSED — STT READY — говорите', then immediately speak one short Russian phrase. After 2–3 seconds call read_russian_system_stt_a1f. Wake listening is resumed automatically after RESULT/ERROR."
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1F", error: describeError(error)};
  }
}

async function read_russian_system_stt_a1f() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const status = parseStatus(Java.callStatic(
      "com.rem.stt.a1f.AndroidSttA1fHelper",
      "getStatusJson",
      context
    ));

    return {
      ok: status.state === "RESULT",
      probe: "W04-A1F",
      ...status
    };
  } catch (error) {
    return {ok: false, probe: "W04-A1F", error: describeError(error)};
  }
}

async function cancel_russian_system_stt_a1f() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();

    Java.callStatic(
      "com.rem.stt.a1f.AndroidSttA1fHelper",
      "cancel",
      context
    );

    return {ok: true, probe: "W04-A1F", cancelled: true};
  } catch (error) {
    return {ok: false, probe: "W04-A1F", error: describeError(error)};
  }
}

exports.start_russian_system_stt_a1f = start_russian_system_stt_a1f;
exports.read_russian_system_stt_a1f = read_russian_system_stt_a1f;
exports.cancel_russian_system_stt_a1f = cancel_russian_system_stt_a1f;
