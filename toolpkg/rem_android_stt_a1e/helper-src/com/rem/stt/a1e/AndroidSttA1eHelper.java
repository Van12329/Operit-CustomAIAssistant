package com.rem.stt.a1e;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AndroidSttA1eHelper {
    private static final String PREFS = "rem_android_stt_a1e";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Session activeSession;

    private AndroidSttA1eHelper() {}

    public static boolean startRussianSystemRecognizer(Context context, int watchdogMs) {
        if (context == null) return false;
        final Context appContext = context.getApplicationContext();
        resetForNewSession(appContext);
        mark(appContext, "requestedElapsedMs", SystemClock.elapsedRealtime());
        writeState(appContext, "SCHEDULED", "", "", 0, "", false, "REQUESTED");
        MAIN.post(() -> startOnMain(appContext, Math.max(10000, watchdogMs)));
        return true;
    }

    public static void cancel(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        MAIN.post(() -> {
            Session session = activeSession;
            if (session != null) {
                session.cancel();
            } else {
                mark(appContext, "cancelElapsedMs", SystemClock.elapsedRealtime());
                writeState(appContext, "CANCELLED", "", "", 0, "", false, "CANCEL_NO_SESSION");
            }
        });
    }

    public static String getStatusJson(Context context) {
        SharedPreferences p = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject();
            o.put("probe", "W04-A1E");
            o.put("state", p.getString("state", "IDLE"));
            o.put("lastEvent", p.getString("lastEvent", ""));
            o.put("text", p.getString("text", ""));
            o.put("partial", p.getString("partial", ""));
            o.put("errorCode", p.getInt("errorCode", 0));
            o.put("error", p.getString("error", ""));
            o.put("ready", p.getBoolean("ready", false));
            o.put("language", "ru-RU");
            o.put("preferOffline", true);
            o.put("systemUi", false);
            o.put("recognizerPath", "createSpeechRecognizer");

            long requested = p.getLong("requestedElapsedMs", 0L);
            long mainStart = p.getLong("mainStartElapsedMs", 0L);
            long startListening = p.getLong("startListeningElapsedMs", 0L);
            long ready = p.getLong("readyElapsedMs", 0L);
            long beginning = p.getLong("beginningElapsedMs", 0L);
            long end = p.getLong("endElapsedMs", 0L);
            long result = p.getLong("resultElapsedMs", 0L);
            long error = p.getLong("errorElapsedMs", 0L);
            long cleanup = p.getLong("cleanupElapsedMs", 0L);

            JSONObject timeline = new JSONObject();
            timeline.put("requested", requested);
            timeline.put("mainStart", mainStart);
            timeline.put("startListening", startListening);
            timeline.put("ready", ready);
            timeline.put("beginning", beginning);
            timeline.put("end", end);
            timeline.put("result", result);
            timeline.put("error", error);
            timeline.put("cleanup", cleanup);
            if (requested > 0 && ready > 0) timeline.put("requestedToReadyMs", ready - requested);
            if (startListening > 0 && ready > 0) timeline.put("startListeningToReadyMs", ready - startListening);
            if (ready > 0 && beginning > 0) timeline.put("readyToBeginningMs", beginning - ready);
            if (beginning > 0 && end > 0) timeline.put("speechDurationMs", end - beginning);
            if (ready > 0 && error > 0) timeline.put("readyToErrorMs", error - ready);
            if (ready > 0 && result > 0) timeline.put("readyToResultMs", result - ready);
            o.put("timeline", timeline);

            String svc = Settings.Secure.getString(
                context.getApplicationContext().getContentResolver(),
                "voice_recognition_service"
            );
            o.put("voiceRecognitionService", svc == null ? "" : svc);
            o.put("updatedAtEpochMs", p.getLong("updatedAtEpochMs", 0L));
            return o.toString();
        } catch (Throwable t) {
            return "{\"state\":\"STATUS_ERROR\",\"error\":\"" + escape(t.toString()) + "\"}";
        }
    }

    private static void startOnMain(Context context, int watchdogMs) {
        mark(context, "mainStartElapsedMs", SystemClock.elapsedRealtime());

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
            writeState(context, "ERROR", "", "", -201, "Helper did not reach Android main thread", false, "MAIN_THREAD_ERROR");
            return;
        }

        if (activeSession != null) {
            activeSession.cancel();
        }

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
                writeState(context, "ERROR", "", "", -202, "SpeechRecognizer is not available", false, "NOT_AVAILABLE");
                return;
            }

            SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            Session session = new Session(context, recognizer, watchdogMs);
            activeSession = session;
            session.start();
        } catch (Throwable t) {
            mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
            writeState(
                context,
                "ERROR",
                "",
                "",
                -204,
                t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()),
                false,
                "START_EXCEPTION"
            );
        }
    }

    private static final class Session implements RecognitionListener {
        private final Context context;
        private final SpeechRecognizer recognizer;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private String lastPartial = "";
        private final Runnable watchdog;

        Session(Context context, SpeechRecognizer recognizer, int watchdogMs) {
            this.context = context;
            this.recognizer = recognizer;
            this.watchdog = () -> {
                if (finished.compareAndSet(false, true)) {
                    mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
                    writeState(context, "ERROR", "", lastPartial, -205, "A1E watchdog timeout", false, "WATCHDOG_TIMEOUT");
                    cleanup();
                }
            };
            MAIN.postDelayed(watchdog, watchdogMs);
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

            mark(context, "startListeningElapsedMs", SystemClock.elapsedRealtime());
            writeState(context, "STARTING", "", "", 0, "", false, "START_LISTENING");
            recognizer.startListening(intent);
        }

        void cancel() {
            if (!finished.compareAndSet(false, true)) return;
            MAIN.removeCallbacks(watchdog);
            mark(context, "cancelElapsedMs", SystemClock.elapsedRealtime());
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            writeState(context, "CANCELLED", "", lastPartial, 0, "", false, "CANCELLED");
            cleanup();
        }

        private void cleanup() {
            MAIN.removeCallbacks(watchdog);
            mark(context, "cleanupElapsedMs", SystemClock.elapsedRealtime());
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
                mark(context, "readyElapsedMs", SystemClock.elapsedRealtime());
                writeState(context, "READY", "", lastPartial, 0, "", true, "READY");
                try {
                    Toast.makeText(context, "STT READY — говорите", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onBeginningOfSpeech() {
            if (!finished.get()) {
                mark(context, "beginningElapsedMs", SystemClock.elapsedRealtime());
                writeState(context, "LISTENING", "", lastPartial, 0, "", true, "BEGINNING_OF_SPEECH");
            }
        }

        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            if (!finished.get()) {
                mark(context, "endElapsedMs", SystemClock.elapsedRealtime());
                writeState(context, "PROCESSING", "", lastPartial, 0, "", true, "END_OF_SPEECH");
            }
        }

        @Override
        public void onError(int error) {
            if (!finished.compareAndSet(false, true)) return;
            mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
            writeState(context, "ERROR", "", lastPartial, error, errorName(error), false, "ERROR");
            cleanup();
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);
            if (!finished.compareAndSet(false, true)) return;
            mark(context, "resultElapsedMs", SystemClock.elapsedRealtime());
            if (text.isEmpty()) {
                writeState(context, "ERROR", "", lastPartial, -206, "Recognition returned no text", false, "EMPTY_RESULT");
            } else {
                writeState(context, "RESULT", text, lastPartial, 0, "", false, "RESULT");
            }
            cleanup();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (!text.isEmpty() && !finished.get()) {
                lastPartial = text;
                writeState(context, "PARTIAL", "", lastPartial, 0, "", true, "PARTIAL");
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

    private static void resetForNewSession(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit();
    }

    private static void mark(Context context, String key, long value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, value)
            .commit();
    }

    private static void writeState(
        Context context,
        String state,
        String text,
        String partial,
        int errorCode,
        String error,
        boolean ready,
        String lastEvent
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("state", state)
            .putString("lastEvent", lastEvent == null ? "" : lastEvent)
            .putString("text", text == null ? "" : text)
            .putString("partial", partial == null ? "" : partial)
            .putInt("errorCode", errorCode)
            .putString("error", error == null ? "" : error)
            .putBoolean("ready", ready)
            .putLong("updatedAtEpochMs", System.currentTimeMillis())
            .commit();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
