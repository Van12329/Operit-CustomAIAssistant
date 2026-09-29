package com.rem.stt.a1d;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AndroidSttA1dHelper {
    private static final String PREFS = "rem_android_stt_a1d";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Session activeSession;

    private AndroidSttA1dHelper() {}

    public static boolean startRussianSystemRecognizer(Context context, int timeoutMs) {
        if (context == null) return false;
        final Context appContext = context.getApplicationContext();
        writeState(appContext, "SCHEDULED", "", "", 0, "", false);
        MAIN.post(() -> startOnMain(appContext, Math.max(5000, timeoutMs)));
        return true;
    }

    public static void cancel(Context context) {
        final Context appContext = context.getApplicationContext();
        MAIN.post(() -> {
            Session session = activeSession;
            if (session != null) {
                session.cancel();
            } else {
                writeState(appContext, "CANCELLED", "", "", 0, "", false);
            }
        });
    }

    public static String getStatusJson(Context context) {
        SharedPreferences p = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject();
            o.put("state", p.getString("state", "IDLE"));
            o.put("text", p.getString("text", ""));
            o.put("partial", p.getString("partial", ""));
            o.put("errorCode", p.getInt("errorCode", 0));
            o.put("error", p.getString("error", ""));
            o.put("ready", p.getBoolean("ready", false));
            o.put("updatedAtMs", p.getLong("updatedAtMs", 0L));
            o.put("language", "ru-RU");
            o.put("preferOffline", true);
            o.put("systemUi", false);
            o.put("recognizerPath", "createSpeechRecognizer");
            String svc = Settings.Secure.getString(
                context.getApplicationContext().getContentResolver(),
                "voice_recognition_service"
            );
            o.put("voiceRecognitionService", svc == null ? "" : svc);
            return o.toString();
        } catch (Throwable t) {
            return "{\"state\":\"STATUS_ERROR\",\"error\":\"" + escape(t.toString()) + "\"}";
        }
    }

    private static void startOnMain(Context context, int timeoutMs) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            writeState(context, "ERROR", "", "", -101, "Helper did not reach Android main thread", false);
            return;
        }

        if (activeSession != null) {
            activeSession.cancel();
        }

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                writeState(context, "ERROR", "", "", -102, "SpeechRecognizer is not available", false);
                return;
            }

            SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            Session session = new Session(context, recognizer, timeoutMs);
            activeSession = session;
            session.start();
        } catch (Throwable t) {
            writeState(
                context,
                "ERROR",
                "",
                "",
                -104,
                t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()),
                false
            );
        }
    }

    private static final class Session implements RecognitionListener {
        private final Context context;
        private final SpeechRecognizer recognizer;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private String lastPartial = "";
        private final Runnable timeoutRunnable;

        Session(Context context, SpeechRecognizer recognizer, int timeoutMs) {
            this.context = context;
            this.recognizer = recognizer;
            this.timeoutRunnable = () -> {
                if (finished.compareAndSet(false, true)) {
                    writeState(context, "ERROR", "", lastPartial, -105, "Recognition timeout", false);
                    cleanup();
                }
            };
            MAIN.postDelayed(timeoutRunnable, timeoutMs);
        }

        void start() {
            recognizer.setRecognitionListener(this);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            );
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

            writeState(context, "STARTING", "", "", 0, "", false);
            recognizer.startListening(intent);
        }

        void cancel() {
            if (!finished.compareAndSet(false, true)) return;
            MAIN.removeCallbacks(timeoutRunnable);
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            writeState(context, "CANCELLED", "", lastPartial, 0, "", false);
            cleanup();
        }

        private void cleanup() {
            MAIN.removeCallbacks(timeoutRunnable);
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            if (activeSession == this) activeSession = null;
        }

        private static String firstResult(Bundle results) {
            if (results == null) return "";
            ArrayList<String> items =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (items == null || items.isEmpty()) return "";
            String value = items.get(0);
            return value == null ? "" : value.trim();
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            if (!finished.get()) {
                writeState(context, "READY", "", lastPartial, 0, "", true);
            }
        }

        @Override
        public void onBeginningOfSpeech() {
            if (!finished.get()) {
                writeState(context, "LISTENING", "", lastPartial, 0, "", true);
            }
        }

        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            if (!finished.get()) {
                writeState(context, "PROCESSING", "", lastPartial, 0, "", true);
            }
        }

        @Override
        public void onError(int error) {
            if (!finished.compareAndSet(false, true)) return;
            writeState(context, "ERROR", "", lastPartial, error, errorName(error), false);
            cleanup();
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);
            if (!finished.compareAndSet(false, true)) return;
            if (text.isEmpty()) {
                writeState(context, "ERROR", "", lastPartial, -106, "Recognition returned no text", false);
            } else {
                writeState(context, "RESULT", text, lastPartial, 0, "", false);
            }
            cleanup();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (!text.isEmpty() && !finished.get()) {
                lastPartial = text;
                writeState(context, "PARTIAL", "", lastPartial, 0, "", true);
            }
        }

        @Override public void onEvent(int eventType, Bundle params) {}

        private static String errorName(int code) {
            switch (code) {
                case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
                case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "ERROR_INSUFFICIENT_PERMISSIONS";
                case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
                case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
                case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
                case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "ERROR_SERVER_DISCONNECTED";
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "ERROR_LANGUAGE_NOT_SUPPORTED";
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "ERROR_LANGUAGE_UNAVAILABLE";
                default: return "ERROR_" + code;
            }
        }
    }

    private static void writeState(
        Context context,
        String state,
        String text,
        String partial,
        int errorCode,
        String error,
        boolean ready
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("state", state)
            .putString("text", text == null ? "" : text)
            .putString("partial", partial == null ? "" : partial)
            .putInt("errorCode", errorCode)
            .putString("error", error == null ? "" : error)
            .putBoolean("ready", ready)
            .putLong("updatedAtMs", System.currentTimeMillis())
            .apply();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
