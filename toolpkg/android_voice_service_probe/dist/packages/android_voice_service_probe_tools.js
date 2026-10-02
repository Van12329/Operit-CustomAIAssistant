/* METADATA
{
  "name": "android_voice_service_probe_tools",
  "display_name": {"en": "Android VoiceService Probe", "zh": "Android VoiceService Probe"},
  "description": {
    "en": "Read-only VoiceServiceFactory injection and Android TTS capability probe.",
    "zh": "Read-only VoiceServiceFactory and Android TTS probe."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "android_voice_service_probe",
      "description": {
        "en": "Verify temporary VoiceServiceFactory proxy injection and enumerate Russian/Spanish Android TTS voices/locales without speaking audio.",
        "zh": "Probe VoiceServiceFactory and TTS voices without audio playback."
      },
      "parameters": []
    }
  ]
}
*/

let loaded = false;

async function ensureLoaded() {
  if (loaded) return true;
  const path = await ToolPkg.readResource(
    "android_voice_service_probe_helper",
    "android-voice-service-probe-helper.dex",
    true
  );
  if (!path) throw new Error("Android VoiceService probe helper resource path is empty");
  Java.loadDex(path, {childFirstPrefixes: ["com.operit.voice.probe."]});
  loaded = true;
  return true;
}

async function android_voice_service_probe() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const raw = Java.callStatic(
      "com.operit.voice.probe.AndroidVoiceServiceProbe",
      "run",
      context
    );
    return JSON.parse(String(raw));
  } catch (error) {
    return {ok: false, error: String(error && error.message ? error.message : error)};
  }
}

exports.android_voice_service_probe = android_voice_service_probe;
