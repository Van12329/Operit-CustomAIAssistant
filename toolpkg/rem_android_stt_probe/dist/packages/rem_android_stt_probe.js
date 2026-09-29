/* METADATA
{
  "name": "rem_android_stt_probe",
  "display_name": {
    "en": "Rem Android STT Probe",
    "zh": "Rem Android STT 探针"
  },
  "description": {
    "en": "Checks Android SpeechRecognizer, on-device availability, audio-source API support, RECORD_AUDIO permission, and whether direct recognizer creation is allowed from the Operit package runtime.",
    "zh": "检测 Android SpeechRecognizer、本地识别、音频源 API、录音权限，以及 Operit 包运行时能否直接创建识别器。"
  },
  "enabledByDefault": true,
  "category": "System",
  "tools": [
    {
      "name": "probe_android_stt",
      "description": {
        "en": "Run a read-only Android STT capability probe. Does not modify Operit settings and does not start microphone recording.",
        "zh": "运行只读 Android STT 能力探针。不修改 Operit 设置，也不启动麦克风录音。"
      },
      "parameters": []
    }
  ]
}
*/

function errText(error) {
  if (error == null) return "";
  try {
    if (error.message) return String(error.message);
  } catch (_) {}
  try {
    return String(error);
  } catch (_) {
    return "unknown error";
  }
}

function safeCall(fn, fallback) {
  try {
    return fn();
  } catch (error) {
    return fallback(error);
  }
}

function probe_android_stt() {
  const result = {
    ok: true,
    probeVersion: "0.1.0",
    timestampMs: Date.now()
  };

  try {
    if (typeof Java === "undefined") {
      return {
        ok: false,
        probeVersion: "0.1.0",
        error: "Java Bridge is not available in this package runtime."
      };
    }

    const BuildVersion = Java.type("android.os.Build$VERSION");
    const SpeechRecognizer = Java.type("android.speech.SpeechRecognizer");
    const RecognizerIntent = Java.type("android.speech.RecognizerIntent");
    const Context = Java.type("android.content.Context");
    const PackageManager = Java.type("android.content.pm.PackageManager");
    const Thread = Java.type("java.lang.Thread");
    const Looper = Java.type("android.os.Looper");
    const SettingsSecure = Java.type("android.provider.Settings$Secure");

    const context = Java.getApplicationContext();
    const sdkInt = Number(BuildVersion.SDK_INT);
    result.sdkInt = sdkInt;
    result.packageName = safeCall(() => String(context.getPackageName()), () => "");
    result.javaBridge = true;
    result.speechRecognizerClass = Java.classExists("android.speech.SpeechRecognizer");

    result.runtimeThread = safeCall(
      () => {
        const current = Thread.currentThread();
        const mainThread = Looper.getMainLooper().getThread();
        return {
          name: String(current.getName()),
          id: Number(current.getId()),
          mainThreadId: Number(mainThread.getId()),
          isMainThread: Number(current.getId()) === Number(mainThread.getId())
        };
      },
      (error) => ({ error: errText(error) })
    );

    result.recordAudioPermissionGranted = safeCall(
      () => Number(context.checkSelfPermission("android.permission.RECORD_AUDIO")) === Number(PackageManager.PERMISSION_GRANTED),
      () => null
    );

    result.recognitionAvailable = safeCall(
      () => !!SpeechRecognizer.callStatic("isRecognitionAvailable", context),
      (error) => ({ error: errText(error) })
    );

    if (sdkInt >= 31) {
      result.onDeviceRecognitionAvailable = safeCall(
        () => !!SpeechRecognizer.callStatic("isOnDeviceRecognitionAvailable", context),
        (error) => ({ error: errText(error) })
      );
    } else {
      result.onDeviceRecognitionAvailable = false;
      result.onDeviceReason = "API < 31";
    }

    result.audioSourceApi = {
      api33OrNewer: sdkInt >= 33,
      extraAudioSource: sdkInt >= 33
        ? safeCall(() => String(RecognizerIntent.EXTRA_AUDIO_SOURCE), (error) => "ERROR: " + errText(error))
        : null,
      extraAudioSourceChannelCount: sdkInt >= 33
        ? safeCall(() => String(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT), (error) => "ERROR: " + errText(error))
        : null,
      extraAudioSourceEncoding: sdkInt >= 33
        ? safeCall(() => String(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING), (error) => "ERROR: " + errText(error))
        : null,
      extraAudioSourceSamplingRate: sdkInt >= 33
        ? safeCall(() => String(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE), (error) => "ERROR: " + errText(error))
        : null
    };

    result.voiceRecognitionService = safeCall(
      () => {
        const resolver = context.getContentResolver();
        const value = SettingsSecure.getString(resolver, "voice_recognition_service");
        return value == null ? "" : String(value);
      },
      (error) => "ERROR: " + errText(error)
    );

    result.directCreateOnDevice = {
      attempted: sdkInt >= 31 && result.onDeviceRecognitionAvailable === true,
      success: false,
      error: ""
    };

    if (result.directCreateOnDevice.attempted) {
      let recognizer = null;
      try {
        recognizer = SpeechRecognizer.callStatic("createOnDeviceSpeechRecognizer", context);
        result.directCreateOnDevice.success = recognizer != null;
      } catch (error) {
        result.directCreateOnDevice.error = errText(error);
      } finally {
        if (recognizer != null) {
          try {
            recognizer.destroy();
          } catch (_) {}
        }
      }
    }

    result.nextDecision =
      result.onDeviceRecognitionAvailable === true
        ? (
            result.directCreateOnDevice.success
              ? "On-device recognizer is available and can be constructed directly from the ToolPkg runtime. Next probe: ru-RU recognition and EXTRA_AUDIO_SOURCE."
              : "On-device recognizer is available, but direct creation failed from the ToolPkg runtime. A tiny DEX helper running SpeechRecognizer calls on Android main thread is the next probe."
          )
        : "On-device recognizer is not exposed to Operit. Try the system recognizer or fallback STT path.";

    return result;
  } catch (error) {
    return {
      ok: false,
      probeVersion: "0.1.0",
      error: errText(error)
    };
  }
}

exports.probe_android_stt = probe_android_stt;
