package com.rem.stt.a1f;

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

public final class AndroidSttA1fHelper {
    private static final String PREFS = "rem_android_stt_a1f";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String AI_SERVICE =
        "com.ai.assistance.operit.api.chat.AIForegroundService";
    private static final String ACTION_SUSPEND_WAKE =
        "com.ai.assistance.operit.action.SET_WAKE_LISTENING_SUSPENDED_FOR_FLOATING_FULLSCREEN";
    private static final String EXTRA_SUSPEND_WAKE =
        "extra_floating_fullscreen_active";

    private static Session activeSession;

    private AndroidSttA1fHelper() {}

    public static boolean startRussianSystemRecognizer(
        Context context,
        int watchdogMs,
        int handoffDelayMs
    ) {
        if (context == null) return false;
        final Context appContext = context.getApplicationContext();

        resetForNewSession(appContext);
        mark(appContext, "requestedElapsedMs", SystemClock.elapsedRealtime());
        writeState(appContext, "SCHEDULED", "", "", 0, "", false, "REQUESTED");

        MAIN.post(() -> {
            if (activeSession != null) {
                activeSession.cancel();
            }

            mark(appContext, "wakeSuspendRequestedElapsedMs", SystemClock.elapsedRealtime());
            boolean sent = requestWakeSuspend(appContext, true);
            setFlag(appContext, "wakeSuspendRequestSent", sent);
            writeState(
                appContext,
                "HANDOFF_WAIT",
                "",
                "",
                0,
                sent ? "" : "Failed to send wake suspend request",
                false,
                "WAKE_SUSPEND_REQUESTED"
            );

            final int delayMs = Math.max(250, Math.min(2000, handoffDelayMs));
            MAIN.postDelayed(
                () -> startOnMain(appContext, Math.max(10000, watchdogMs)),
                delayMs
            );
        });

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
                resumeWake(appContext);
                writeState(appContext, "CANCELLED", "", "", 0, "", false, "CANCEL_NO_SESSION");
            }
        });
    }

    public static String getStatusJson(Context context) {
        SharedPreferences p = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        try {
            JSONObject o = new JSONObject();
            o.put("probe", "W04-A1F");
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
            o.put("wakeSuspendRequestSent", p.getBoolean("wakeSuspendRequestSent", false));
            o.put("wakeResumeRequestSent", p.getBoolean("wakeResumeRequestSent", false));

            long requested = p.getLong("requestedElapsedMs", 0L);
            long suspend = p.getLong("wakeSuspendRequestedElapsedMs", 0L);
            long mainStart = p.getLong("mainStartElapsedMs", 0L);
            long startListening = p.getLong("startListeningElapsedMs", 0L);
            long ready = p.getLong("readyElapsedMs", 0L);
            long beginning = p.getLong("beginningElapsedMs", 0L);
            long end = p.getLong("endElapsedMs", 0L);
            long result = p.getLong("resultElapsedMs", 0L);
            long error = p.getLong("errorElapsedMs", 0L);
            long cleanup = p.getLong("cleanupElapsedMs", 0L);
            long resume = p.getLong("wakeResumeRequestedElapsedMs", 0L);

            JSONObject timeline = new JSONObject();
            timeline.put("requested", requested);
            timeline.put("wakeSuspendRequested", suspend);
            timeline.put("mainStart", mainStart);
            timeline.put("startListening", startListening);
            timeline.put("ready", ready);
            timeline.put("beginning", beginning);
            timeline.put("end", end);
            timeline.put("result", result);
            timeline.put("error", error);
            timeline.put("cleanup", cleanup);
            timeline.put("wakeResumeRequested", resume);

            if (suspend > 0 && startListening > 0) {
                timeline.put("suspendToStartListeningMs", startListening - suspend);
            }
            if (requested > 0 && ready > 0) {
                timeline.put("requestedToReadyMs", ready - requested);
            }
            if (startListening > 0 && ready > 0) {
                timeline.put("startListeningToReadyMs", ready - startListening);
            }
            if (ready > 0 && beginning > 0) {
                timeline.put("readyToBeginningMs", beginning - ready);
            }
            if (beginning > 0 && end > 0) {
                timeline.put("speechDurationMs", end - beginning);
            }
            if (ready > 0 && error > 0) {
                timeline.put("readyToErrorMs", error - ready);
            }
            if (ready > 0 && result > 0) {
                timeline.put("readyToResultMs", result - ready);
            }

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
            writeState(
                context, "ERROR", "", "", -301,
                "Helper did not reach Android main thread",
                false, "MAIN_THREAD_ERROR"
            );
            resumeWake(context);
            return;
        }

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
                writeState(
                    context, "ERROR", "", "", -302,
                    "SpeechRecognizer is not available",
                    false, "NOT_AVAILABLE"
                );
                resumeWake(context);
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
                -304,
                t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()),
                false,
                "START_EXCEPTION"
            );
            resumeWake(context);
        }
    }

    private static boolean requestWakeSuspend(Context context, boolean suspend) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), AI_SERVICE);
            intent.setAction(ACTION_SUSPEND_WAKE);
            intent.putExtra(EXTRA_SUSPEND_WAKE, suspend);
            context.startService(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resumeWake(Context context) {
        mark(context, "wakeResumeRequestedElapsedMs", SystemClock.elapsedRealtime());
        boolean sent = requestWakeSuspend(context, false);
        setFlag(context, "wakeResumeRequestSent", sent);
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
                    writeState(
                        context, "ERROR", "", lastPartial, -305,
                        "A1F watchdog timeout", false, "WATCHDOG_TIMEOUT"
                    );
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

            try {
                recognizer.cancel();
            } catch (Throwable ignored) {}

            writeState(
                context, "CANCELLED", "", lastPartial, 0, "",
                false, "CANCELLED"
            );
            cleanup();
        }

        private void cleanup() {
            MAIN.removeCallbacks(watchdog);

            try {
                recognizer.destroy();
            } catch (Throwable ignored) {}

            mark(context, "cleanupElapsedMs", SystemClock.elapsedRealtime());
            resumeWake(context);

            if (activeSession == this) {
                activeSession = null;
            }
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
                    Toast.makeText(
                        context,
                        "WAKE PAUSED — STT READY — говорите",
                        Toast.LENGTH_SHORT
                    ).show();
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onBeginningOfSpeech() {
            if (!finished.get()) {
                mark(context, "beginningElapsedMs", SystemClock.elapsedRealtime());
                writeState(
                    context, "LISTENING", "", lastPartial, 0, "",
                    true, "BEGINNING_OF_SPEECH"
                );
            }
        }

        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            if (!finished.get()) {
                mark(context, "endElapsedMs", SystemClock.elapsedRealtime());
                writeState(
                    context, "PROCESSING", "", lastPartial, 0, "",
                    true, "END_OF_SPEECH"
                );
            }
        }

        @Override
        public void onError(int error) {
            if (!finished.compareAndSet(false, true)) return;

            mark(context, "errorElapsedMs", SystemClock.elapsedRealtime());
            writeState(
                context, "ERROR", "", lastPartial, error,
                errorName(error), false, "ERROR"
            );
            cleanup();
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);

            if (!finished.compareAndSet(false, true)) return;

            mark(context, "resultElapsedMs", SystemClock.elapsedRealtime());

            if (text.isEmpty()) {
                writeState(
                    context, "ERROR", "", lastPartial, -306,
                    "Recognition returned no text",
                    false, "EMPTY_RESULT"
                );
            } else {
                writeState(
                    context, "RESULT", text, lastPartial, 0, "",
                    false, "RESULT"
                );
            }

            cleanup();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);

            if (!text.isEmpty() && !finished.get()) {
                lastPartial = text;
                writeState(
                    context, "PARTIAL", "", lastPartial, 0, "",
                    true, "PARTIAL"
                );
            }
        }

        @Override public void onEvent(int eventType, Bundle params) {}

        private static String errorName(int code) {
            switch (code) {
                case SpeechRecognizer.ERROR_AUDIO:
                    return "ERROR_AUDIO";
                case SpeechRecognizer.ERROR_CLIENT:
                    return "ERROR_CLIENT";
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    return "ERROR_INSUFFICIENT_PERMISSIONS";
                case SpeechRecognizer.ERROR_NETWORK:
                    return "ERROR_NETWORK";
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    return "ERROR_NETWORK_TIMEOUT";
                case SpeechRecognizer.ERROR_NO_MATCH:
                    return "ERROR_NO_MATCH";
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    return "ERROR_RECOGNIZER_BUSY";
                case SpeechRecognizer.ERROR_SERVER:
                    return "ERROR_SERVER";
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    return "ERROR_SPEECH_TIMEOUT";
                case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                    return "ERROR_SERVER_DISCONNECTED";
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                    return "ERROR_LANGUAGE_NOT_SUPPORTED";
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    return "ERROR_LANGUAGE_UNAVAILABLE";
                default:
                    return "ERROR_" + code;
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

    private static void setFlag(Context context, String key, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
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

    private static boolean readFlag(Context context, String key) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, false);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
