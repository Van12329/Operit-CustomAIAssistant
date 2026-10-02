/* METADATA
{
  "name": "android_speech_service_adapter_tools",
  "display_name": {"en": "Android SpeechService Adapter", "zh": "Android SpeechService Adapter"},
  "description": {
    "en": "Status, language, capability, and model-download controls for the Android SpeechService adapter.",
    "zh": "Android SpeechService adapter controls."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "android_speech_service_status",
      "description": {
        "en": "Read injection, language, recognition and last-result status.",
        "zh": "Read Android SpeechService adapter status."
      },
      "parameters": []
    },
    {
      "name": "android_speech_service_set_language",
      "description": {
        "en": "Set the Android recognition locale. Configured locales: ru-RU, es-AR, es-ES. Operit's hardcoded zh-CN is ignored.",
        "zh": "Set recognition locale."
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
      "name": "android_speech_service_check_support",
      "description": {
        "en": "Query the Android recognizer for installed, downloadable, pending, and online support for a locale. Does not use the microphone.",
        "zh": "Query recognition support for a locale without using the microphone."
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
      "name": "android_speech_service_request_model_download",
      "description": {
        "en": "Request on-device recognition support download for a locale. The Android recognizer may show system UI or schedule the download.",
        "zh": "Request on-device speech model download for a locale."
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
      "name": "android_speech_service_model_download_status",
      "description": {
        "en": "Read observable Android model-download state for the active locale request: requested, downloading, scheduled, success, or error.",
        "zh": "Read Android speech model download status."
      },
      "parameters": []
    },
    {
      "name": "android_speech_service_uninstall_runtime",
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
    "android_speech_service_adapter_helper",
    "android-speech-service-adapter-helper.dex",
    true
  );
  if (!path) throw new Error("Android SpeechService adapter helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.operit.speech.adapter."]});
  helperLoaded = true;
  return true;
}

async function android_speech_service_status() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "install",
      context
    );
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "getStatusJson",
      context
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_speech_service_set_language(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const language = String(params && params.language ? params.language : "");
    const ok = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "setLanguage",
      context,
      language
    );
    return {ok: !!ok, language};
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_speech_service_check_support(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const language = String(params && params.language ? params.language : "");
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "checkRecognitionSupportJson",
      context,
      language
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_speech_service_request_model_download(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const language = String(params && params.language ? params.language : "");
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "requestModelDownloadJson",
      context,
      language
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_speech_service_model_download_status() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "getModelDownloadStatusJson",
      context
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_speech_service_uninstall_runtime() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const ok = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "uninstall",
      context
    );
    return {ok: !!ok, restored: !!ok};
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

exports.android_speech_service_status = android_speech_service_status;
exports.android_speech_service_set_language = android_speech_service_set_language;
exports.android_speech_service_check_support = android_speech_service_check_support;
exports.android_speech_service_request_model_download = android_speech_service_request_model_download;
exports.android_speech_service_model_download_status = android_speech_service_model_download_status;
exports.android_speech_service_uninstall_runtime = android_speech_service_uninstall_runtime;
