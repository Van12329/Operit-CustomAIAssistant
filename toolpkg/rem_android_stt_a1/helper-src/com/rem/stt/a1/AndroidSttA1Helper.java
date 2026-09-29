package com.rem.stt.a1;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AndroidSttA1Helper {
    public interface Callback {
        void onReady();
        void onPartial(String text);
        void onResult(String text);
        void onError(int code, String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Session activeSession;

    private AndroidSttA1Helper() {}

    public static void startRussianMicTest(Context context, int timeoutMs, Callback callback) {
        if (context == null) {
            callback.onError(-100, "Context is null");
            return;
        }
        final Context appContext = context.getApplicationContext();
        MAIN.post(new Runnable() {
            @Override public void run() {
                startOnMain(appContext, timeoutMs, callback);
            }
        });
    }

    public static void cancel() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                Session session = activeSession;
                if (session != null) {
                    session.cancel();
                }
            }
        });
    }

    private static void startOnMain(Context context, int timeoutMs, Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            callback.onError(-101, "Helper did not reach Android main thread");
            return;
        }

        if (activeSession != null) {
            activeSession.cancel();
        }

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                callback.onError(-102, "SpeechRecognizer is not available");
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                callback.onError(-103, "On-device SpeechRecognizer is not available");
                return;
            }

            SpeechRecognizer recognizer =
                android.os.Build.VERSION.SDK_INT >= 31
                    ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    : SpeechRecognizer.createSpeechRecognizer(context);

            Session session = new Session(recognizer, callback, Math.max(5000, timeoutMs));
            activeSession = session;
            session.start();
        } catch (Throwable t) {
            callback.onError(-104, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static final class Session implements RecognitionListener {
        private final SpeechRecognizer recognizer;
        private final Callback callback;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Runnable timeoutRunnable;

        Session(SpeechRecognizer recognizer, Callback callback, int timeoutMs) {
            this.recognizer = recognizer;
            this.callback = callback;
            this.timeoutRunnable = new Runnable() {
                @Override public void run() {
                    if (finished.compareAndSet(false, true)) {
                        cleanup();
                        callback.onError(-105, "Recognition timeout");
                    }
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

            recognizer.startListening(intent);
        }

        void cancel() {
            if (!finished.compareAndSet(false, true)) return;
            MAIN.removeCallbacks(timeoutRunnable);
            try { recognizer.cancel(); } catch (Throwable ignored) {}
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

        @Override public void onReadyForSpeech(Bundle params) {
            if (!finished.get()) callback.onReady();
        }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}

        @Override public void onError(int error) {
            if (!finished.compareAndSet(false, true)) return;
            MAIN.removeCallbacks(timeoutRunnable);
            cleanup();
            callback.onError(error, errorName(error));
        }

        @Override public void onResults(Bundle results) {
            String text = firstResult(results);
            if (!finished.compareAndSet(false, true)) return;
            MAIN.removeCallbacks(timeoutRunnable);
            cleanup();
            if (text.isEmpty()) callback.onError(-106, "Recognition returned no text");
            else callback.onResult(text);
        }

        @Override public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (!text.isEmpty() && !finished.get()) callback.onPartial(text);
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
                default: return "ERROR_" + code;
            }
        }
    }
}
