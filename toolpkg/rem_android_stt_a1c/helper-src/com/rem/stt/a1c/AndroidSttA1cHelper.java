package com.rem.stt.a1c;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executor;

public final class AndroidSttA1cHelper {
    private static final String PREFS = "rem_android_stt_a1c";
    private static final String KEY_SUPPORT = "support_json";
    private static final String KEY_DOWNLOAD = "download_json";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AndroidSttA1cHelper() {}

    public static boolean checkRussianOnDeviceSupport(Context context) {
        if (context == null) return false;
        final Context appContext = context.getApplicationContext();
        write(appContext, KEY_SUPPORT, json("SCHEDULED", 0, ""));
        MAIN.post(() -> checkSupportOnMain(appContext));
        return true;
    }

    public static boolean triggerRussianModelDownload(Context context) {
        if (context == null) return false;
        final Context appContext = context.getApplicationContext();
        write(appContext, KEY_DOWNLOAD, json("SCHEDULED", 0, ""));
        MAIN.post(() -> triggerDownloadOnMain(appContext));
        return true;
    }

    public static String getSupportJson(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SUPPORT, json("IDLE", 0, ""));
    }

    public static String getDownloadJson(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOAD, json("IDLE", 0, ""));
    }

    private static void checkSupportOnMain(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            write(context, KEY_SUPPORT, json("ERROR", -1, "API < 33"));
            return;
        }

        SpeechRecognizer recognizer = null;
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                write(context, KEY_SUPPORT, json("ERROR", -2, "On-device recognizer unavailable"));
                return;
            }

            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            final SpeechRecognizer recognizerRef = recognizer;
            Intent intent = buildRussianIntent();
            Executor executor = context.getMainExecutor();

            write(context, KEY_SUPPORT, json("CHECKING", 0, ""));

            recognizer.checkRecognitionSupport(
                intent,
                executor,
                new RecognitionSupportCallback() {
                    @Override
                    public void onSupportResult(RecognitionSupport support) {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("state", "RESULT");
                            o.put("language", "ru-RU");
                            o.put("installedOnDeviceLanguages", toJson(support.getInstalledOnDeviceLanguages()));
                            o.put("pendingOnDeviceLanguages", toJson(support.getPendingOnDeviceLanguages()));
                            o.put("supportedOnDeviceLanguages", toJson(support.getSupportedOnDeviceLanguages()));
                            o.put("onlineLanguages", toJson(support.getOnlineLanguages()));
                            o.put("updatedAtMs", System.currentTimeMillis());
                            write(context, KEY_SUPPORT, o.toString());
                        } catch (Throwable t) {
                            write(context, KEY_SUPPORT, json("ERROR", -3, t.toString()));
                        } finally {
                            try { recognizerRef.destroy(); } catch (Throwable ignored) {}
                        }
                    }

                    @Override
                    public void onError(int error) {
                        write(context, KEY_SUPPORT, json("ERROR", error, errorName(error)));
                        try { recognizerRef.destroy(); } catch (Throwable ignored) {}
                    }
                }
            );
        } catch (Throwable t) {
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Throwable ignored) {}
            }
            write(context, KEY_SUPPORT, json("ERROR", -4,
                t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
        }
    }

    private static void triggerDownloadOnMain(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            write(context, KEY_DOWNLOAD, json("ERROR", -1, "API < 33"));
            return;
        }

        SpeechRecognizer recognizer = null;
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                write(context, KEY_DOWNLOAD, json("ERROR", -2, "On-device recognizer unavailable"));
                return;
            }

            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            final SpeechRecognizer recognizerRef = recognizer;
            Intent intent = buildRussianIntent();

            if (Build.VERSION.SDK_INT >= 34) {
                write(context, KEY_DOWNLOAD, json("DOWNLOADING", 0, ""));
                recognizer.triggerModelDownload(
                    intent,
                    context.getMainExecutor(),
                    new ModelDownloadListener() {
                        @Override
                        public void onProgress(int completedPercent) {
                            try {
                                JSONObject o = new JSONObject();
                                o.put("state", "PROGRESS");
                                o.put("percent", completedPercent);
                                o.put("language", "ru-RU");
                                o.put("updatedAtMs", System.currentTimeMillis());
                                write(context, KEY_DOWNLOAD, o.toString());
                            } catch (Throwable ignored) {}
                        }

                        @Override
                        public void onSuccess() {
                            write(context, KEY_DOWNLOAD, json("SUCCESS", 0, ""));
                            try { recognizerRef.destroy(); } catch (Throwable ignored) {}
                        }

                        @Override
                        public void onScheduled() {
                            write(context, KEY_DOWNLOAD, json("SCHEDULED_BY_SERVICE", 0, ""));
                            try { recognizerRef.destroy(); } catch (Throwable ignored) {}
                        }

                        @Override
                        public void onError(int error) {
                            write(context, KEY_DOWNLOAD, json("ERROR", error, errorName(error)));
                            try { recognizerRef.destroy(); } catch (Throwable ignored) {}
                        }
                    }
                );
            } else {
                recognizer.triggerModelDownload(intent);
                write(context, KEY_DOWNLOAD, json("TRIGGERED", 0, ""));
                try { recognizer.destroy(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Throwable ignored) {}
            }
            write(context, KEY_DOWNLOAD, json("ERROR", -4,
                t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage())));
        }
    }

    private static Intent buildRussianIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        return intent;
    }

    private static JSONArray toJson(List<String> values) {
        JSONArray a = new JSONArray();
        if (values != null) {
            for (String value : values) a.put(value);
        }
        return a;
    }

    private static String json(String state, int errorCode, String error) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("language", "ru-RU");
            o.put("errorCode", errorCode);
            o.put("error", error == null ? "" : error);
            o.put("updatedAtMs", System.currentTimeMillis());
            return o.toString();
        } catch (Throwable t) {
            return "{\"state\":\"JSON_ERROR\"}";
        }
    }

    private static void write(Context context, String key, String value) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(key, value).apply();
    }

    private static String errorName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "ERROR_TOO_MANY_REQUESTS";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "ERROR_SERVER_DISCONNECTED";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "ERROR_LANGUAGE_NOT_SUPPORTED";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "ERROR_LANGUAGE_UNAVAILABLE";
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT: return "ERROR_CANNOT_CHECK_SUPPORT";
            case SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS: return "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS";
            default: return "ERROR_" + code;
        }
    }
}
