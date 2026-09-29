/* METADATA
{
  "name": "rem_android_stt_a1",
  "display_name": {"en": "Rem Android STT A1", "zh": "Rem Android STT A1"},
  "description": {
    "en": "Real Russian on-device Android SpeechRecognizer test without system recognition UI.",
    "zh": "Android STT A1"
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "test_russian_ondevice_stt_a1",
      "description": {
        "en": "Start Android on-device Russian speech recognition without system UI. Speak one short Russian phrase after the call starts.",
        "zh": "Start Android on-device Russian STT without system UI."
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
      "name": "cancel_russian_ondevice_stt_a1",
      "description": {"en": "Cancel an active A1 recognition test.", "zh": "Cancel A1."},
      "parameters": []
    }
  ]
}
*/

function describeError(error) {
  try {
    if (error && error.message) return String(error.message);
  } catch (_) {}
  try { return String(error); } catch (_) { return "unknown error"; }
}

async function ensureHelperLoaded() {
  const helperPath = await ToolPkg.readResource(
    "android_stt_a1_helper",
    "rem-android-stt-a1-helper.dex",
    true
  );
  if (!helperPath) throw new Error("Helper resource path is empty");
  Java.loadDex(helperPath, {childFirstPrefixes: ["com.rem.stt.a1."]});
  return helperPath;
}

async function test_russian_ondevice_stt_a1(params) {
  const timeoutMs = Math.max(
    5000,
    Math.min(30000, Number(params && params.timeout_ms) || 20000)
  );

  try {
    const helperPath = await ensureHelperLoaded();
    const context = Java.getApplicationContext();

    return await new Promise((resolve) => {
      let done = false;
      let ready = false;
      let lastPartial = "";

      function finish(value) {
        if (done) return;
        done = true;
        resolve(value);
      }

      const callback = Java.implement(
        "com.rem.stt.a1.AndroidSttA1Helper$Callback",
        {
          onReady() {
            ready = true;
          },
          onPartial(text) {
            lastPartial = String(text || "");
          },
          onResult(text) {
            finish({
              ok: true,
              probe: "W04-A1",
              language: "ru-RU",
              onDevice: true,
              systemUi: false,
              ready,
              text: String(text || ""),
              lastPartial,
              helperPath
            });
          },
          onError(code, message) {
            finish({
              ok: false,
              probe: "W04-A1",
              language: "ru-RU",
              onDevice: true,
              systemUi: false,
              ready,
              errorCode: Number(code),
              error: String(message || ""),
              lastPartial,
              helperPath
            });
          }
        }
      );

      Java.callStatic(
        "com.rem.stt.a1.AndroidSttA1Helper",
        "startRussianMicTest",
        context,
        timeoutMs,
        callback
      );
    });
  } catch (error) {
    return {ok: false, probe: "W04-A1", error: describeError(error)};
  }
}

async function cancel_russian_ondevice_stt_a1() {
  try {
    await ensureHelperLoaded();
    Java.callStatic("com.rem.stt.a1.AndroidSttA1Helper", "cancel");
    return {ok: true, cancelled: true};
  } catch (error) {
    return {ok: false, error: describeError(error)};
  }
}

exports.test_russian_ondevice_stt_a1 = test_russian_ondevice_stt_a1;
exports.cancel_russian_ondevice_stt_a1 = cancel_russian_ondevice_stt_a1;
