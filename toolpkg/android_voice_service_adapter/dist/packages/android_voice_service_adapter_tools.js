/* METADATA
{
  "name": "android_voice_service_adapter_tools",
  "display_name": {"en": "Android VoiceService Adapter", "zh": "Android VoiceService Adapter"},
  "description": {
    "en": "Status and runtime controls for multilingual Android TTS routing.",
    "zh": "Multilingual Android TTS routing controls."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "android_voice_service_status",
      "description": {
        "en": "Read VoiceServiceFactory injection, multilingual TTS routing and duplex coordination status.",
        "zh": "Read Android VoiceService adapter status."
      },
      "parameters": []
    },
    {
      "name": "android_voice_service_uninstall_runtime",
      "description": {
        "en": "Restore the original VoiceServiceFactory instance for this process. Force-stop/restart after using this control.",
        "zh": "Restore original VoiceServiceFactory runtime state."
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
    "android_voice_service_adapter_helper",
    "android-voice-service-adapter-helper.dex",
    true
  );
  if (!path) throw new Error("Android VoiceService adapter helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.operit.voice.adapter."]});
  helperLoaded = true;
  return true;
}

async function android_voice_service_status() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    Java.callStatic(
      "com.operit.voice.adapter.AndroidVoiceServiceInjector",
      "install",
      context
    );
    const raw = Java.callStatic(
      "com.operit.voice.adapter.AndroidVoiceServiceInjector",
      "getStatusJson",
      context
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

async function android_voice_service_uninstall_runtime() {
  try {
    await ensureLoaded();
    const ok = Java.callStatic(
      "com.operit.voice.adapter.AndroidVoiceServiceInjector",
      "uninstallRuntime"
    );
    return {ok: !!ok};
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

exports.android_voice_service_status = android_voice_service_status;
exports.android_voice_service_uninstall_runtime = android_voice_service_uninstall_runtime;
