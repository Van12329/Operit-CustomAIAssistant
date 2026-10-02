/* METADATA
{
  "name": "rem_android_speech_service_n02_tools",
  "display_name": {"en": "Rem Native Android SpeechService N02", "zh": "Rem Native Android SpeechService N02"},
  "description": {
    "en": "Status and language controls for the N02 native SpeechService injection.",
    "zh": "N02 SpeechService controls."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "rem_android_speech_service_n02_status",
      "description": {
        "en": "Read N02 injection, language, recognition and last-result status.",
        "zh": "Read N02 status."
      },
      "parameters": []
    },
    {
      "name": "rem_android_speech_service_n02_set_language",
      "description": {
        "en": "Set the actual Android recognition locale used by N02. Supported: ru-RU, es-AR, es-ES. Operit's hardcoded zh-CN is ignored.",
        "zh": "Set N02 recognition locale."
      },
      "parameters": [
        {
          "name": "language",
          "type": "string",
          "required": true,
          "description": {
            "en": "ru-RU, es-AR, or es-ES",
            "zh": "ru-RU, es-AR, or es-ES"
          }
        }
      ]
    },
    {
      "name": "rem_android_speech_service_n02_uninstall_runtime",
      "description": {
        "en": "Restore the original SpeechServiceFactory instance for this process. Force-stop/restart after using this control.",
        "zh": "Restore original SpeechServiceFactory runtime state."
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
    "android_speech_service_n02_helper",
    "android-speech-service-n02-helper.dex",
    true
  );
  if (!path) throw new Error("N02 helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.nativeprovider."]});
  helperLoaded = true;
  return true;
}

async function rem_android_speech_service_n02_status() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    Java.callStatic(
      "com.rem.stt.nativeprovider.AndroidSpeechServiceInjector",
      "install",
      context
    );
    const raw = Java.callStatic(
      "com.rem.stt.nativeprovider.AndroidSpeechServiceInjector",
      "getStatusJson",
      context
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {n02: true, ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function rem_android_speech_service_n02_set_language(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const language = String(params && params.language ? params.language : "");
    const ok = Java.callStatic(
      "com.rem.stt.nativeprovider.AndroidSpeechServiceInjector",
      "setLanguage",
      context,
      language
    );
    return {n02: true, ok: !!ok, language};
  } catch (error) {
    return {n02: true, ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function rem_android_speech_service_n02_uninstall_runtime() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const ok = Java.callStatic(
      "com.rem.stt.nativeprovider.AndroidSpeechServiceInjector",
      "uninstall",
      context
    );
    return {n02: true, ok: !!ok, restored: !!ok};
  } catch (error) {
    return {n02: true, ok: false, error: String(error && error.message ? error.message : error)};
  }
}

exports.rem_android_speech_service_n02_status = rem_android_speech_service_n02_status;
exports.rem_android_speech_service_n02_set_language = rem_android_speech_service_n02_set_language;
exports.rem_android_speech_service_n02_uninstall_runtime = rem_android_speech_service_n02_uninstall_runtime;
