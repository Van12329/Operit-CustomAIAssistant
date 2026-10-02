"use strict";

let helperLoaded = false;

async function ensureHelper() {
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

async function installN02() {
  await ensureHelper();
  const context = Java.getApplicationContext();
  return !!Java.callStatic(
    "com.rem.stt.nativeprovider.AndroidSpeechServiceInjector",
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
    await installN02();
  } catch (error) {
    console.log("[rem_n02] lifecycle injection failed: " + String(error));
  }
  return null;
}

function registerToolPkg() {
  ToolPkg.registerAppLifecycleHook({
    id: "rem_n02_on_create",
    event: "application_on_create",
    function: onApplicationLifecycle
  });
  ToolPkg.registerAppLifecycleHook({
    id: "rem_n02_on_foreground",
    event: "application_on_foreground",
    function: onApplicationLifecycle
  });

  Promise.resolve(installN02()).catch((error) => {
    console.log("[rem_n02] bootstrap failed: " + String(error));
  });
  return true;
}

exports.registerToolPkg = registerToolPkg;
exports.onApplicationLifecycle = onApplicationLifecycle;
