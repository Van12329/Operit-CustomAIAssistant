/* METADATA
{
  "name": "rem_android_stt_a1c",
  "display_name": {"en": "Rem Android STT A1C", "zh": "Rem Android STT A1C"},
  "description": {
    "en": "Checks whether the ru-RU on-device recognition model is installed, pending, or downloadable.",
    "zh": "Checks ru-RU on-device model support."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "check_russian_ondevice_support_a1c",
      "description": {"en": "Start a non-blocking support check for the ru-RU on-device recognition model.", "zh": "Check ru-RU support."},
      "parameters": []
    },
    {
      "name": "read_russian_ondevice_support_a1c",
      "description": {"en": "Read the latest ru-RU on-device recognition support result.", "zh": "Read support."},
      "parameters": []
    },
    {
      "name": "download_russian_ondevice_model_a1c",
      "description": {"en": "Trigger download of the ru-RU on-device recognition model if supported but not installed.", "zh": "Download ru-RU model."},
      "parameters": []
    },
    {
      "name": "read_russian_model_download_a1c",
      "description": {"en": "Read current ru-RU model download status.", "zh": "Read download status."},
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
    "android_stt_a1c_helper",
    "rem-android-stt-a1c-helper.dex",
    true
  );
  if (!path) throw new Error("Helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.a1c."]});
  return path;
}

function parse(raw) {
  try { return JSON.parse(String(raw)); }
  catch (_) { return {state:"PARSE_ERROR", raw:String(raw)}; }
}

async function check_russian_ondevice_support_a1c() {
  try {
    const helperPath = await ensureLoaded();
    const context = Java.getApplicationContext();
    const accepted = Java.callStatic(
      "com.rem.stt.a1c.AndroidSttA1cHelper",
      "checkRussianOnDeviceSupport",
      context
    );
    return {ok: !!accepted, probe:"W04-A1C", helperPath, instruction:"Call read_russian_ondevice_support_a1c after 1–2 seconds."};
  } catch (error) {
    return {ok:false, probe:"W04-A1C", error:describeError(error)};
  }
}

async function read_russian_ondevice_support_a1c() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    return {
      probe:"W04-A1C",
      ...parse(Java.callStatic("com.rem.stt.a1c.AndroidSttA1cHelper","getSupportJson",context))
    };
  } catch (error) {
    return {state:"TOOL_ERROR", error:describeError(error)};
  }
}

async function download_russian_ondevice_model_a1c() {
  try {
    const helperPath = await ensureLoaded();
    const context = Java.getApplicationContext();
    const accepted = Java.callStatic(
      "com.rem.stt.a1c.AndroidSttA1cHelper",
      "triggerRussianModelDownload",
      context
    );
    return {ok:!!accepted, helperPath, instruction:"Call read_russian_model_download_a1c after a few seconds."};
  } catch (error) {
    return {ok:false, error:describeError(error)};
  }
}

async function read_russian_model_download_a1c() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    return parse(Java.callStatic("com.rem.stt.a1c.AndroidSttA1cHelper","getDownloadJson",context));
  } catch (error) {
    return {state:"TOOL_ERROR", error:describeError(error)};
  }
}

exports.check_russian_ondevice_support_a1c = check_russian_ondevice_support_a1c;
exports.read_russian_ondevice_support_a1c = read_russian_ondevice_support_a1c;
exports.download_russian_ondevice_model_a1c = download_russian_ondevice_model_a1c;
exports.read_russian_model_download_a1c = read_russian_model_download_a1c;
