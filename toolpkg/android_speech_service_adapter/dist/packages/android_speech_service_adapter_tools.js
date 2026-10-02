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
        "en": "Read injection, AUTO language switching, coordinated TTS/STT ownership, lexical continuation gating, formatting, recognition and last-result status.",
        "zh": "Read Android SpeechService adapter status."
      },
      "parameters": []
    },
    {
      "name": "android_speech_service_set_auto_language",
      "description": {
        "en": "Enable automatic ru-RU/es-US recognition language switching. This is the normal mode; manual set_language remains an override.",
        "zh": "Enable automatic ru-RU/es-US language switching."
      },
      "parameters": []
    },
    {
      "name": "android_speech_service_set_language",
      "description": {
        "en": "Manual language override. Sets a fixed recognition locale and disables AUTO until android_speech_service_set_auto_language is called.",
        "zh": "Set a manual recognition locale override."
      },
      "parameters": [
        {
          "name": "language",
          "type": "string",
          "required": true,
          "description": {
            "en": "ru-RU, es-US, es-419, es-AR, or es-ES",
            "zh": "ru-RU, es-US, es-419, es-AR, or es-ES"
          }
        }
      ]
    },
    {
      "name": "android_speech_service_list_system_languages",
      "description": {
        "en": "Read the installed voice-recognition service's language-details broadcast without using the microphone or requesting downloads. Returns the language preference and any supported-language list the service chooses to publish.",
        "zh": "Read voice recognition language details without using the microphone or downloading models."
      },
      "parameters": []
    },
    {
      "name": "android_speech_service_probe_on_device",
      "description": {
        "en": "Probe Android's dedicated on-device SpeechRecognizer for a locale without using the microphone. Reports on-device recognizer availability and, when supported by the service, installed/downloadable language capability.",
        "zh": "Probe the dedicated on-device SpeechRecognizer without using the microphone."
      },
      "parameters": [
        {
          "name": "language",
          "type": "string",
          "required": true,
          "description": {
            "en": "ru-RU, es-US, es-419, es-AR, or es-ES",
            "zh": "ru-RU, es-US, es-419, es-AR, or es-ES"
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
            "en": "ru-RU, es-US, es-419, es-AR, or es-ES",
            "zh": "ru-RU, es-US, es-419, es-AR, or es-ES"
          }
        }
      ]
    },
    {
      "name": "android_speech_service_request_on_device_model_download",
      "description": {
        "en": "Request installation of a speech model from Android's dedicated on-device recognizer. Does not use the microphone. On Android 14+ reports progress/scheduled/success/error through the shared model-download status.",
        "zh": "Request an on-device speech model download without using the microphone."
      },
      "parameters": [
        {
          "name": "language",
          "type": "string",
          "required": true,
          "description": {
            "en": "ru-RU, es-US, es-419, es-AR, or es-ES",
            "zh": "ru-RU, es-US, es-419, es-AR, or es-ES"
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
            "en": "ru-RU, es-US, es-419, es-AR, or es-ES",
            "zh": "ru-RU, es-US, es-419, es-AR, or es-ES"
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

async function android_speech_service_set_auto_language() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const ok = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "setAutoLanguageMode",
      context
    );
    return {ok: !!ok, mode: "AUTO", allowedLanguages: ["ru-RU", "es-US"]};
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

async function android_speech_service_list_system_languages() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "getVoiceLanguageDetailsJson",
      context
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_speech_service_probe_on_device(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const language = String(params && params.language ? params.language : "");
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "probeOnDeviceRecognitionJson",
      context,
      language
    );
    return JSON.parse(String(raw));
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

async function android_speech_service_request_on_device_model_download(params) {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const language = String(params && params.language ? params.language : "");
    const raw = Java.callStatic(
      "com.operit.speech.adapter.AndroidSpeechServiceInjector",
      "requestOnDeviceModelDownloadJson",
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
exports.android_speech_service_set_auto_language = android_speech_service_set_auto_language;
exports.android_speech_service_set_language = android_speech_service_set_language;
exports.android_speech_service_list_system_languages = android_speech_service_list_system_languages;
exports.android_speech_service_probe_on_device = android_speech_service_probe_on_device;
exports.android_speech_service_check_support = android_speech_service_check_support;
exports.android_speech_service_request_on_device_model_download = android_speech_service_request_on_device_model_download;
exports.android_speech_service_request_model_download = android_speech_service_request_model_download;
exports.android_speech_service_model_download_status = android_speech_service_model_download_status;
exports.android_speech_service_uninstall_runtime = android_speech_service_uninstall_runtime;
