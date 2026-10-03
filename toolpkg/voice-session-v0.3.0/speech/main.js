"use strict";

let helperLoaded = false;

async function ensureHelper() {
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

async function installAdapter() {
  await ensureHelper();
  const context = Java.getApplicationContext();
  return !!Java.callStatic(
    "com.operit.speech.adapter.AndroidSpeechServiceInjector",
    "install",
    context
  );
}

async function onApplicationLifecycle(input) {
  const eventName = String(input && input.eventName ? input.eventName : "");
  if (eventName !== "application_on_create" && eventName !== "application_on_foreground") {
    return null;
  }
  try {
    await installAdapter();
  } catch (error) {
    console.log("[android_speech_service_adapter] lifecycle injection failed: " + String(error));
  }
  return null;
}

function registerToolPkg() {
  ToolPkg.registerAppLifecycleHook({
    id: "android_speech_service_adapter_on_create",
    event: "application_on_create",
    function: onApplicationLifecycle
  });
  ToolPkg.registerAppLifecycleHook({
    id: "android_speech_service_adapter_on_foreground",
    event: "application_on_foreground",
    function: onApplicationLifecycle
  });

  Promise.resolve(installAdapter()).catch((error) => {
    console.log("[android_speech_service_adapter] bootstrap failed: " + String(error));
  });
  return true;
}

exports.registerToolPkg = registerToolPkg;
exports.onApplicationLifecycle = onApplicationLifecycle;
