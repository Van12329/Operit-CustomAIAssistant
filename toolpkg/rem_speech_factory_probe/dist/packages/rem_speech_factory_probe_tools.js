/* METADATA
{
  "name": "rem_speech_factory_probe_tools",
  "display_name": {"en": "Rem Speech Factory Probe", "zh": "Rem Speech Factory Probe"},
  "description": {
    "en": "Passive N01 probe. Does not start STT or touch the microphone.",
    "zh": "Passive N01 factory injection probe."
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "rem_speech_factory_injection_probe",
      "description": {
        "en": "Temporarily inject a no-op SpeechService proxy, verify SpeechServiceFactory and a newly constructed SpeechInteractionManager capture it, then restore the original factory state. Does not initialize or start recognition.",
        "zh": "Verify SpeechServiceFactory injection without starting recognition."
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
    "speech_factory_probe_helper",
    "speech-factory-probe-helper.dex",
    true
  );
  if (!path) throw new Error("Speech factory probe helper resource path is empty");

  Java.loadDex(path, {childFirstPrefixes: ["com.rem.stt.probe."]});
  helperLoaded = true;
  return true;
}

async function rem_speech_factory_injection_probe() {
  try {
    await ensureLoaded();
    const context = Java.getApplicationContext();
    const raw = Java.callStatic(
      "com.rem.stt.probe.SpeechFactoryInjectionProbe",
      "runProbe",
      context
    );

    let parsed;
    try { parsed = JSON.parse(String(raw)); }
    catch (_) { parsed = {ok: false, parseError: true, raw: String(raw)}; }

    return {
      n01: true,
      ...parsed
    };
  } catch (error) {
    return {
      n01: true,
      ok: false,
      error: String(error && error.message ? error.message : error)
    };
  }
}

exports.rem_speech_factory_injection_probe = rem_speech_factory_injection_probe;
