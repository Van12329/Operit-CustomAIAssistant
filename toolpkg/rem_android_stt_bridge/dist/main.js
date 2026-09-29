"use strict";

let helperLoaded = false;
let helperPath = "";

async function ensureHelper() {
    if (helperLoaded) return helperPath;

    helperPath = await ToolPkg.readResource(
        "android_stt_bridge_helper",
        "rem-android-stt-bridge-helper.dex",
        true
    );
    if (!helperPath) throw new Error("Android STT bridge helper resource path is empty");

    Java.loadDex(helperPath, {
        childFirstPrefixes: ["com.rem.stt.bridge."]
    });
    helperLoaded = true;
    return helperPath;
}

async function installBridge() {
    await ensureHelper();
    const context = Java.getApplicationContext();
    return !!Java.callStatic(
        "com.rem.stt.bridge.AndroidSttBridgeHelper",
        "install",
        context
    );
}

async function onApplicationLifecycle(input) {
    const eventName = String(input && input.eventName ? input.eventName : "");
    if (
        eventName !== "application_on_create" &&
        eventName !== "application_on_foreground"
    ) {
        return null;
    }

    try {
        await installBridge();
    } catch (error) {
        console.log("[rem_android_stt_bridge] lifecycle install failed: " + String(error));
    }
    return null;
}

async function onChatRuntime(input) {
    try {
        const payload = (input && input.eventPayload) || {};
        if (String(payload.slot || "") !== "floating") return null;

        await ensureHelper();
        const context = Java.getApplicationContext();

        Java.callStatic(
            "com.rem.stt.bridge.AndroidSttBridgeHelper",
            "onFloatingRuntimeState",
            context,
            String(payload.state || ""),
            !!payload.isActive
        );
    } catch (error) {
        console.log("[rem_android_stt_bridge] runtime hook failed: " + String(error));
    }
    return null;
}

function registerToolPkg() {
    ToolPkg.registerAppLifecycleHook({
        id: "rem_android_stt_bridge_on_create",
        event: "application_on_create",
        function: onApplicationLifecycle
    });

    ToolPkg.registerAppLifecycleHook({
        id: "rem_android_stt_bridge_on_foreground",
        event: "application_on_foreground",
        function: onApplicationLifecycle
    });

    ToolPkg.registerChatRuntimeHook({
        id: "rem_android_stt_bridge_runtime",
        function: onChatRuntime
    });

    Promise.resolve(installBridge()).catch((error) => {
        console.log("[rem_android_stt_bridge] bootstrap failed: " + String(error));
    });

    return true;
}

exports.registerToolPkg = registerToolPkg;
exports.onApplicationLifecycle = onApplicationLifecycle;
exports.onChatRuntime = onChatRuntime;
