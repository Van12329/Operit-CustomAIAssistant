package com.operit.speech.adapter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * adapter: installs Android SpeechRecognizer as Operit's SpeechService instance.
 *
 * The stock SpeechInteractionManager remains the sole voice-session controller.
 * The languageCode supplied by Operit is intentionally ignored because current
 * Operit hardcodes zh-CN; the active locale is owned by this adapter instead.
 */
public final class AndroidSpeechServiceInjector {
    private static final String FACTORY =
        "com.ai.assistance.operit.api.speech.SpeechServiceFactory";
    private static final String SPEECH_SERVICE =
        "com.ai.assistance.operit.api.speech.SpeechService";
    private static final String RECOGNITION_STATE =
        "com.ai.assistance.operit.api.speech.SpeechService$RecognitionState";
    private static final String RECOGNITION_RESULT =
        "com.ai.assistance.operit.api.speech.SpeechService$RecognitionResult";
    private static final String RECOGNITION_ERROR =
        "com.ai.assistance.operit.api.speech.SpeechService$RecognitionError";
    private static final String STATE_FLOW_KT =
        "kotlinx.coroutines.flow.StateFlowKt";
    private static final String MUTABLE_STATE_FLOW =
        "kotlinx.coroutines.flow.MutableStateFlow";

    private static final String PREFS = "operit_android_speech_service_adapter";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_LANGUAGE_MODE = "language_mode";
    private static final String DEFAULT_LANGUAGE = "ru-RU";
    private static final String MODE_AUTO = "AUTO";
    private static final String MODE_MANUAL = "MANUAL";
    private static final String AUTO_ALLOWED_LANGUAGES = "ru-RU,es-US";
    private static final long CONTINUATION_RESTART_DELAY_MS = 120L;
    private static final long CONTINUATION_HOLD_MS = 1100L;
    private static final long CONTINUATION_LEXICAL_GRACE_MS = 300L;
    private static final long KEEPALIVE_MIN_INTERVAL_MS = 500L;

    private static final String VOICE_FACTORY =
        "com.ai.assistance.operit.api.voice.VoiceServiceFactory";

    private static final String DUPLEX_PREFS = "operit_voice_duplex_coordination";
    private static final String DUPLEX_KEY_SEQ = "tts_seq";
    private static final String DUPLEX_KEY_STATE = "tts_state";
    private static final String DUPLEX_KEY_REQUEST_AT = "tts_request_at_elapsed";
    private static final long DUPLEX_COORD_FRESH_MS = 30_000L;

    private static final long TTS_GATE_POLL_MS = 50L;
    private static final long TTS_GATE_WAKE_DISCOVERY_MS = 600L;
    private static final long TTS_GATE_IDLE_TAIL_MS = 120L;
    private static final long TTS_GATE_MAX_WAIT_MS = 15_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Executor DIRECT_EXECUTOR =
        new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };

    private static Context app;
    private static Field instanceField;
    private static Field profileField;
    private static Object factorySingleton;
    private static Method factoryGetInstance;

    private static Object originalInstance;
    private static String originalProfileId;
    private static Object injectedProxy;
    private static AndroidSpeechHandler handler;
    private static boolean installed;
    private static String installError = "";

    // API 34+ observable model-download state.
    private static SpeechRecognizer modelDownloadRecognizer;
    private static String modelDownloadLanguage = "";
    private static String modelDownloadState = "IDLE";
    private static int modelDownloadProgress = -1;
    private static int modelDownloadErrorCode = 0;
    private static String modelDownloadErrorName = "";
    private static long modelDownloadUpdatedAtMs = 0L;

    private AndroidSpeechServiceInjector() {}

    public static boolean install(Context context) {
        synchronized (LOCK) {
            try {
                app = context.getApplicationContext();
                if (installed && injectedProxy != null) {
                    return true;
                }

                ClassLoader cl = app.getClassLoader();
                Class<?> factoryClass = Class.forName(FACTORY, false, cl);
                Class<?> speechServiceClass = Class.forName(SPEECH_SERVICE, false, cl);

                factorySingleton = factoryClass.getField("INSTANCE").get(null);
                factoryGetInstance = factoryClass.getMethod("getInstance", Context.class);

                instanceField = factoryClass.getDeclaredField("instance");
                profileField = factoryClass.getDeclaredField("currentProfileId");
                instanceField.setAccessible(true);
                profileField.setAccessible(true);

                Object beforeInstance = instanceField.get(null);
                Object beforeProfile = profileField.get(null);

                if ((beforeInstance == null) != (beforeProfile == null)) {
                    throw new IllegalStateException(
                        "SpeechServiceFactory fields inconsistent before adapter injection"
                    );
                }

                if (beforeInstance == null) {
                    // Establish currentProfileId without initializing or starting recognition.
                    factoryGetInstance.invoke(factorySingleton, app);
                    beforeInstance = instanceField.get(null);
                    beforeProfile = profileField.get(null);
                }

                if (beforeInstance == null || !(beforeProfile instanceof String)) {
                    throw new IllegalStateException(
                        "Could not establish factory baseline for adapter"
                    );
                }

                originalInstance = beforeInstance;
                originalProfileId = (String) beforeProfile;

                handler = new AndroidSpeechHandler(app, cl);
                injectedProxy = Proxy.newProxyInstance(
                    speechServiceClass.getClassLoader(),
                    new Class<?>[] { speechServiceClass },
                    handler
                );

                instanceField.set(null, injectedProxy);
                profileField.set(null, originalProfileId);

                Object verify = factoryGetInstance.invoke(factorySingleton, app);
                if (verify != injectedProxy) {
                    instanceField.set(null, originalInstance);
                    profileField.set(null, originalProfileId);
                    injectedProxy = null;
                    handler = null;
                    throw new IllegalStateException(
                        "SpeechServiceFactory did not retain adapter proxy"
                    );
                }

                installed = true;
                installError = "";
                return true;
            } catch (Throwable t) {
                installError = describe(t);
                installed = false;
                return false;
            }
        }
    }

    public static boolean uninstall(Context context) {
        synchronized (LOCK) {
            boolean ok = true;
            try {
                if (handler != null) {
                    handler.shutdown();
                }
            } catch (Throwable ignored) {
                ok = false;
            }

            try {
                if (instanceField != null && profileField != null) {
                    instanceField.set(null, originalInstance);
                    profileField.set(null, originalProfileId);
                }
            } catch (Throwable ignored) {
                ok = false;
            }

            try {
                destroyModelDownloadRecognizerOnMain();
            } catch (Throwable ignored) {
                ok = false;
            }

            installed = false;
            injectedProxy = null;
            handler = null;
            return ok;
        }
    }

    public static boolean setLanguage(Context context, String requested) {
        Context a = context.getApplicationContext();
        String normalized = normalizeLanguage(requested);
        if (normalized == null) return false;
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .putString(KEY_LANGUAGE_MODE, MODE_MANUAL)
            .apply();
        if (handler != null) handler.noteConfiguredLanguage(normalized, MODE_MANUAL);
        return true;
    }

    public static boolean setAutoLanguageMode(Context context) {
        Context a = context.getApplicationContext();
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_MODE, MODE_AUTO)
            .apply();
        if (handler != null) {
            handler.noteConfiguredLanguage(getConfiguredLanguage(a), MODE_AUTO);
        }
        return true;
    }

    public static String getStatusJson(Context context) {
        JSONObject o = new JSONObject();
        try {
            Context a = context.getApplicationContext();
            String configured = getConfiguredLanguage(a);
            String languageMode = getLanguageMode(a);
            boolean factoryPointsToProxy = false;

            synchronized (LOCK) {
                if (instanceField != null && injectedProxy != null) {
                    try {
                        factoryPointsToProxy = instanceField.get(null) == injectedProxy;
                    } catch (Throwable ignored) {
                    }
                }

                o.put("n02", true);
                o.put("installed", installed);
                o.put("factoryPointsToProxy", factoryPointsToProxy);
                o.put("configuredLanguage", configured);
                o.put("activeLanguage", configured);
                o.put("languageMode", languageMode);
                o.put("autoAllowedLanguages", AUTO_ALLOWED_LANGUAGES);
                o.put("autoSwitchApiAvailable", Build.VERSION.SDK_INT >= 34);
                o.put("configuredLocales", "ru-RU,es-US,es-419,es-AR,es-ES");
                o.put("partialPolicy", "FINAL_ONLY_TO_OPERIT");
                o.put("installError", installError);
                o.put(
                    "proxyClass",
                    injectedProxy == null ? JSONObject.NULL : injectedProxy.getClass().getName()
                );
                o.put(
                    "originalInstanceClass",
                    originalInstance == null
                        ? JSONObject.NULL
                        : originalInstance.getClass().getName()
                );

                if (handler != null) {
                    handler.appendStatus(o);
                }
            }
        } catch (Throwable t) {
            try {
                o.put("statusError", describe(t));
            } catch (Throwable ignored) {
            }
        }
        return o.toString();
    }

    public static String getVoiceLanguageDetailsJson(Context context) {
        JSONObject out = new JSONObject();
        HandlerThread thread = null;

        try {
            final Context a = context.getApplicationContext();
            final Intent detailsIntent = RecognizerIntent.getVoiceDetailsIntent(a);

            out.put("probe", "VOICE_LANGUAGE_DETAILS");
            out.put("microphoneUsed", false);
            out.put("downloadRequested", false);

            if (detailsIntent == null) {
                out.put("ok", false);
                out.put("voiceDetailsIntentAvailable", false);
                out.put("error", "RecognizerIntent.getVoiceDetailsIntent returned null");
                return out.toString();
            }

            out.put("voiceDetailsIntentAvailable", true);
            if (detailsIntent.getComponent() != null) {
                out.put("receiverComponent", detailsIntent.getComponent().flattenToShortString());
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Bundle> extrasRef = new AtomicReference<>();
            final AtomicReference<Integer> resultCodeRef = new AtomicReference<>();

            thread = new HandlerThread("SpeechLanguageDetailsProbe");
            thread.start();
            Handler receiverHandler = new Handler(thread.getLooper());

            BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        resultCodeRef.set(getResultCode());
                        Bundle extras = getResultExtras(true);
                        extrasRef.set(extras == null ? new Bundle() : new Bundle(extras));
                        latch.countDown();
                    }
                };

            a.sendOrderedBroadcast(
                detailsIntent,
                null,
                receiver,
                receiverHandler,
                0,
                null,
                null
            );

            if (!latch.await(6000L, TimeUnit.MILLISECONDS)) {
                out.put("ok", false);
                out.put("error", "Voice language details broadcast timeout");
                return out.toString();
            }

            Bundle extras = extrasRef.get();
            if (extras == null) extras = new Bundle();

            List<String> languages =
                extras.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES);
            String preference =
                extras.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE);

            out.put(
                "resultCode",
                resultCodeRef.get() == null ? JSONObject.NULL : resultCodeRef.get()
            );
            out.put(
                "languagePreference",
                preference == null ? JSONObject.NULL : preference
            );
            out.put(
                "reportedSupportedLanguages",
                languages == null ? new JSONArray() : new JSONArray(languages)
            );
            out.put("reportedLanguageCount", languages == null ? 0 : languages.size());
            out.put("hasEsUS", containsLanguage(languages, "es-US"));
            out.put("hasEs419", containsLanguage(languages, "es-419"));
            out.put("hasEsAR", containsLanguage(languages, "es-AR"));
            out.put("hasEsES", containsLanguage(languages, "es-ES"));
            out.put("hasRuRU", containsLanguage(languages, "ru-RU"));
            out.put("ok", true);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
            } catch (Throwable ignored) {
            }
        } finally {
            if (thread != null) {
                try {
                    thread.quitSafely();
                } catch (Throwable ignored) {
                }
            }
        }

        return out.toString();
    }

    public static String probeOnDeviceRecognitionJson(Context context, String requested) {
        JSONObject out = new JSONObject();
        SpeechRecognizer probe = null;

        try {
            final Context a = context.getApplicationContext();
            final String language = normalizeLanguage(requested);

            out.put("probe", "ON_DEVICE_RECOGNIZER");
            out.put("apiLevel", Build.VERSION.SDK_INT);
            out.put("defaultRecognitionAvailable", SpeechRecognizer.isRecognitionAvailable(a));
            out.put(
                "onDeviceRecognitionAvailable",
                Build.VERSION.SDK_INT >= 31
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(a)
            );

            if (language == null) {
                out.put("ok", false);
                out.put("error", "Unsupported adapter locale: " + String.valueOf(requested));
                return out.toString();
            }
            out.put("requestedLanguage", language);

            if (Build.VERSION.SDK_INT < 31) {
                out.put("ok", false);
                out.put("error", "On-device SpeechRecognizer requires Android API 31+");
                return out.toString();
            }

            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) {
                out.put("ok", false);
                out.put("error", "No on-device recognition service is available");
                return out.toString();
            }

            final SpeechRecognizer created =
                runOnMainSync(
                    new MainCallable<SpeechRecognizer>() {
                        @Override
                        public SpeechRecognizer call() {
                            return SpeechRecognizer.createOnDeviceSpeechRecognizer(a);
                        }
                    },
                    2500L
                );
            probe = created;
            out.put("created", true);

            if (Build.VERSION.SDK_INT < 33) {
                out.put("ok", true);
                out.put("supportQueryAvailable", false);
                return out.toString();
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<RecognitionSupport> supportRef = new AtomicReference<>();
            final AtomicReference<Integer> errorRef = new AtomicReference<>();
            final Intent intent = buildRecognitionIntent(language, true, false);

            runOnMainSync(
                new MainCallable<Boolean>() {
                    @Override
                    public Boolean call() {
                        created.checkRecognitionSupport(
                            intent,
                            DIRECT_EXECUTOR,
                            new RecognitionSupportCallback() {
                                @Override
                                public void onSupportResult(RecognitionSupport recognitionSupport) {
                                    supportRef.set(recognitionSupport);
                                    latch.countDown();
                                }

                                @Override
                                public void onError(int error) {
                                    errorRef.set(error);
                                    latch.countDown();
                                }
                            }
                        );
                        return Boolean.TRUE;
                    }
                },
                2500L
            );

            if (!latch.await(6000L, TimeUnit.MILLISECONDS)) {
                out.put("ok", false);
                out.put("supportQueryAvailable", true);
                out.put("error", "On-device RecognitionSupport callback timeout");
                return out.toString();
            }

            Integer supportError = errorRef.get();
            if (supportError != null) {
                out.put("ok", false);
                out.put("supportQueryAvailable", true);
                out.put("supportErrorCode", supportError.intValue());
                out.put("supportErrorName", recognitionErrorName(supportError.intValue()));
                out.put("error", "On-device RecognitionSupport query failed");
                return out.toString();
            }

            RecognitionSupport support = supportRef.get();
            if (support == null) {
                out.put("ok", false);
                out.put("supportQueryAvailable", true);
                out.put("error", "On-device RecognitionSupport returned no data");
                return out.toString();
            }

            List<String> installed = support.getInstalledOnDeviceLanguages();
            List<String> pending = support.getPendingOnDeviceLanguages();
            List<String> supported = support.getSupportedOnDeviceLanguages();
            List<String> online = support.getOnlineLanguages();

            out.put("supportQueryAvailable", true);
            out.put("installedOnDeviceLanguages", new JSONArray(installed));
            out.put("pendingOnDeviceLanguages", new JSONArray(pending));
            out.put("supportedOnDeviceLanguages", new JSONArray(supported));
            out.put("onlineLanguages", new JSONArray(online));
            out.put("requestedInstalledOnDevice", containsLanguage(installed, language));
            out.put("requestedPendingOnDevice", containsLanguage(pending, language));
            out.put("requestedSupportedOnDevice", containsLanguage(supported, language));
            out.put("requestedOnline", containsLanguage(online, language));
            out.put("ok", true);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
            } catch (Throwable ignored) {
            }
        } finally {
            final SpeechRecognizer toDestroy = probe;
            if (toDestroy != null) {
                try {
                    runOnMainSync(
                        new MainCallable<Boolean>() {
                            @Override
                            public Boolean call() {
                                toDestroy.destroy();
                                return Boolean.TRUE;
                            }
                        },
                        2500L
                    );
                } catch (Throwable ignored) {
                }
            }
        }

        return out.toString();
    }

    public static String checkRecognitionSupportJson(Context context, String requested) {
        JSONObject out = new JSONObject();
        SpeechRecognizer probe = null;

        try {
            Context a = context.getApplicationContext();
            String language = normalizeLanguage(requested);
            if (language == null) {
                out.put("ok", false);
                out.put("error", "Unsupported adapter locale: " + String.valueOf(requested));
                return out.toString();
            }

            out.put("ok", false);
            out.put("requestedLanguage", language);
            out.put("preferOffline", true);
            out.put("apiLevel", Build.VERSION.SDK_INT);

            if (Build.VERSION.SDK_INT < 33) {
                out.put("error", "RecognitionSupport requires Android API 33+");
                return out.toString();
            }

            final SpeechRecognizer created =
                runOnMainSync(
                    new MainCallable<SpeechRecognizer>() {
                        @Override
                        public SpeechRecognizer call() {
                            return SpeechRecognizer.createSpeechRecognizer(a);
                        }
                    },
                    2500L
                );
            probe = created;

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<RecognitionSupport> supportRef = new AtomicReference<>();
            final AtomicReference<Integer> errorRef = new AtomicReference<>();

            final Intent intent = buildRecognitionIntent(language, true, false);

            runOnMainSync(
                new MainCallable<Boolean>() {
                    @Override
                    public Boolean call() {
                        created.checkRecognitionSupport(
                            intent,
                            DIRECT_EXECUTOR,
                            new RecognitionSupportCallback() {
                                @Override
                                public void onSupportResult(RecognitionSupport recognitionSupport) {
                                    supportRef.set(recognitionSupport);
                                    latch.countDown();
                                }

                                @Override
                                public void onError(int error) {
                                    errorRef.set(error);
                                    latch.countDown();
                                }
                            }
                        );
                        return Boolean.TRUE;
                    }
                },
                2500L
            );

            if (!latch.await(6000L, TimeUnit.MILLISECONDS)) {
                out.put("error", "RecognitionSupport callback timeout");
                return out.toString();
            }

            Integer supportError = errorRef.get();
            if (supportError != null) {
                out.put("supportErrorCode", supportError.intValue());
                out.put("supportErrorName", recognitionErrorName(supportError.intValue()));
                out.put("error", "RecognitionSupport query failed");
                return out.toString();
            }

            RecognitionSupport support = supportRef.get();
            if (support == null) {
                out.put("error", "RecognitionSupport callback returned no data");
                return out.toString();
            }

            List<String> installed = support.getInstalledOnDeviceLanguages();
            List<String> pending = support.getPendingOnDeviceLanguages();
            List<String> supported = support.getSupportedOnDeviceLanguages();
            List<String> online = support.getOnlineLanguages();

            out.put("installedOnDeviceLanguages", new JSONArray(installed));
            out.put("pendingOnDeviceLanguages", new JSONArray(pending));
            out.put("supportedOnDeviceLanguages", new JSONArray(supported));
            out.put("onlineLanguages", new JSONArray(online));

            out.put("requestedInstalledOnDevice", containsLanguage(installed, language));
            out.put("requestedPendingOnDevice", containsLanguage(pending, language));
            out.put("requestedSupportedOnDevice", containsLanguage(supported, language));
            out.put("requestedOnline", containsLanguage(online, language));
            out.put("ok", true);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
            } catch (Throwable ignored) {
            }
        } finally {
            final SpeechRecognizer toDestroy = probe;
            if (toDestroy != null) {
                try {
                    runOnMainSync(
                        new MainCallable<Boolean>() {
                            @Override
                            public Boolean call() {
                                toDestroy.destroy();
                                return Boolean.TRUE;
                            }
                        },
                        2500L
                    );
                } catch (Throwable ignored) {
                }
            }
        }

        return out.toString();
    }

    public static String requestOnDeviceModelDownloadJson(Context context, String requested) {
        JSONObject out = new JSONObject();

        try {
            final Context a = context.getApplicationContext();
            final String language = normalizeLanguage(requested);

            out.put("probe", "ON_DEVICE_MODEL_DOWNLOAD");
            out.put("requestedLanguage", language == null ? JSONObject.NULL : language);
            out.put("apiLevel", Build.VERSION.SDK_INT);
            out.put("microphoneUsed", false);
            out.put("onDeviceRecognitionAvailable",
                Build.VERSION.SDK_INT >= 31
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(a)
            );

            if (language == null) {
                out.put("ok", false);
                out.put("error", "Unsupported adapter locale: " + String.valueOf(requested));
                return out.toString();
            }

            if (Build.VERSION.SDK_INT < 31
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(a)) {
                out.put("ok", false);
                out.put("error", "Dedicated on-device SpeechRecognizer is unavailable");
                return out.toString();
            }

            synchronized (LOCK) {
                if ("DOWNLOADING".equals(modelDownloadState)
                    || "REQUESTED".equals(modelDownloadState)) {
                    out.put("ok", false);
                    out.put("error", "A model download request is already active.");
                    appendModelDownloadStatus(out);
                    return out.toString();
                }
            }

            final Intent intent = buildRecognitionIntent(language, true, false);

            runOnMainSync(
                new MainCallable<Boolean>() {
                    @Override
                    public Boolean call() {
                        destroyModelDownloadRecognizerOnMain();

                        modelDownloadRecognizer =
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(a);
                        modelDownloadLanguage = language;
                        modelDownloadState = "REQUESTED";
                        modelDownloadProgress = -1;
                        modelDownloadErrorCode = 0;
                        modelDownloadErrorName = "";
                        modelDownloadUpdatedAtMs = System.currentTimeMillis();

                        final SpeechRecognizer active = modelDownloadRecognizer;

                        if (Build.VERSION.SDK_INT >= 34) {
                            active.triggerModelDownload(
                                intent,
                                a.getMainExecutor(),
                                new ModelDownloadListener() {
                                    @Override
                                    public void onProgress(int completedPercent) {
                                        synchronized (LOCK) {
                                            modelDownloadState = "DOWNLOADING";
                                            modelDownloadProgress = completedPercent;
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                    }

                                    @Override
                                    public void onScheduled() {
                                        synchronized (LOCK) {
                                            modelDownloadState = "SCHEDULED";
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                        destroyModelDownloadRecognizerOnMain();
                                    }

                                    @Override
                                    public void onSuccess() {
                                        synchronized (LOCK) {
                                            modelDownloadState = "SUCCESS";
                                            modelDownloadProgress = 100;
                                            modelDownloadErrorCode = 0;
                                            modelDownloadErrorName = "";
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                        destroyModelDownloadRecognizerOnMain();
                                    }

                                    @Override
                                    public void onError(int error) {
                                        synchronized (LOCK) {
                                            modelDownloadState = "ERROR";
                                            modelDownloadErrorCode = error;
                                            modelDownloadErrorName =
                                                recognitionErrorName(error);
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                        destroyModelDownloadRecognizerOnMain();
                                    }
                                }
                            );
                        } else {
                            active.triggerModelDownload(intent);
                            modelDownloadState = "REQUESTED_UNOBSERVED";
                            modelDownloadUpdatedAtMs = System.currentTimeMillis();
                            destroyModelDownloadRecognizerOnMain();
                        }

                        return Boolean.TRUE;
                    }
                },
                2500L
            );

            out.put("ok", true);
            out.put("listenerEnabled", Build.VERSION.SDK_INT >= 34);
            appendModelDownloadStatus(out);
            out.put(
                "nextStep",
                "Call android_speech_service_model_download_status until SUCCESS/SCHEDULED/ERROR."
            );
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
                appendModelDownloadStatus(out);
            } catch (Throwable ignored) {
            }
        }

        return out.toString();
    }

    public static String requestModelDownloadJson(Context context, String requested) {
        JSONObject out = new JSONObject();

        try {
            final Context a = context.getApplicationContext();
            final String language = normalizeLanguage(requested);
            if (language == null) {
                out.put("ok", false);
                out.put("error", "Unsupported adapter locale: " + String.valueOf(requested));
                return out.toString();
            }

            out.put("requestedLanguage", language);
            out.put("preferOffline", true);
            out.put("apiLevel", Build.VERSION.SDK_INT);

            if (Build.VERSION.SDK_INT < 33) {
                out.put("ok", false);
                out.put("error", "Model download requires Android API 33+");
                return out.toString();
            }

            synchronized (LOCK) {
                if ("DOWNLOADING".equals(modelDownloadState)
                    || "REQUESTED".equals(modelDownloadState)) {
                    out.put("ok", false);
                    out.put("error", "A model download request is already active.");
                    appendModelDownloadStatus(out);
                    return out.toString();
                }
            }

            final Intent intent = buildRecognitionIntent(language, true, false);

            if (Build.VERSION.SDK_INT >= 34) {
                runOnMainSync(
                    new MainCallable<Boolean>() {
                        @Override
                        public Boolean call() {
                            destroyModelDownloadRecognizerOnMain();

                            modelDownloadRecognizer =
                                SpeechRecognizer.createSpeechRecognizer(a);
                            modelDownloadLanguage = language;
                            modelDownloadState = "REQUESTED";
                            modelDownloadProgress = -1;
                            modelDownloadErrorCode = 0;
                            modelDownloadErrorName = "";
                            modelDownloadUpdatedAtMs = System.currentTimeMillis();

                            final SpeechRecognizer active = modelDownloadRecognizer;
                            active.triggerModelDownload(
                                intent,
                                a.getMainExecutor(),
                                new ModelDownloadListener() {
                                    @Override
                                    public void onProgress(int completedPercent) {
                                        synchronized (LOCK) {
                                            modelDownloadState = "DOWNLOADING";
                                            modelDownloadProgress = completedPercent;
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                    }

                                    @Override
                                    public void onScheduled() {
                                        synchronized (LOCK) {
                                            modelDownloadState = "SCHEDULED";
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                        destroyModelDownloadRecognizerOnMain();
                                    }

                                    @Override
                                    public void onSuccess() {
                                        synchronized (LOCK) {
                                            modelDownloadState = "SUCCESS";
                                            modelDownloadProgress = 100;
                                            modelDownloadErrorCode = 0;
                                            modelDownloadErrorName = "";
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                        destroyModelDownloadRecognizerOnMain();
                                    }

                                    @Override
                                    public void onError(int error) {
                                        synchronized (LOCK) {
                                            modelDownloadState = "ERROR";
                                            modelDownloadErrorCode = error;
                                            modelDownloadErrorName =
                                                recognitionErrorName(error);
                                            modelDownloadUpdatedAtMs =
                                                System.currentTimeMillis();
                                        }
                                        destroyModelDownloadRecognizerOnMain();
                                    }
                                }
                            );
                            return Boolean.TRUE;
                        }
                    },
                    2500L
                );

                out.put("ok", true);
                out.put("listenerEnabled", true);
                appendModelDownloadStatus(out);
                out.put(
                    "nextStep",
                    "Call android_speech_service_model_download_status to read progress/success/error."
                );
                return out.toString();
            }

            // Android 13 fallback: the old API is fire-and-forget.
            final SpeechRecognizer created =
                runOnMainSync(
                    new MainCallable<SpeechRecognizer>() {
                        @Override
                        public SpeechRecognizer call() {
                            return SpeechRecognizer.createSpeechRecognizer(a);
                        }
                    },
                    2500L
                );

            try {
                runOnMainSync(
                    new MainCallable<Boolean>() {
                        @Override
                        public Boolean call() {
                            created.triggerModelDownload(intent);
                            return Boolean.TRUE;
                        }
                    },
                    2500L
                );
            } finally {
                runOnMainSync(
                    new MainCallable<Boolean>() {
                        @Override
                        public Boolean call() {
                            created.destroy();
                            return Boolean.TRUE;
                        }
                    },
                    2500L
                );
            }

            synchronized (LOCK) {
                modelDownloadLanguage = language;
                modelDownloadState = "REQUESTED_UNOBSERVED";
                modelDownloadProgress = -1;
                modelDownloadErrorCode = 0;
                modelDownloadErrorName = "";
                modelDownloadUpdatedAtMs = System.currentTimeMillis();
            }

            out.put("ok", true);
            out.put("listenerEnabled", false);
            appendModelDownloadStatus(out);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
                appendModelDownloadStatus(out);
            } catch (Throwable ignored) {
            }
        }

        return out.toString();
    }

    public static String getModelDownloadStatusJson(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("apiLevel", Build.VERSION.SDK_INT);
            appendModelDownloadStatus(out);
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    private static void appendModelDownloadStatus(JSONObject out) throws Exception {
        synchronized (LOCK) {
            out.put("language", modelDownloadLanguage);
            out.put("state", modelDownloadState);
            out.put("progressPercent", modelDownloadProgress);
            out.put("errorCode", modelDownloadErrorCode);
            out.put("errorName", modelDownloadErrorName);
            out.put("updatedAtEpochMs", modelDownloadUpdatedAtMs);
        }
    }

    private static void destroyModelDownloadRecognizerOnMain() {
        SpeechRecognizer current = modelDownloadRecognizer;
        modelDownloadRecognizer = null;
        if (current != null) {
            try {
                current.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Intent buildRecognitionIntent(
        String language,
        boolean preferOffline,
        boolean partialResults
    ) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        return intent;
    }

    private static Intent buildRuntimeRecognitionIntent(
        String language,
        boolean preferOffline,
        boolean partialResults,
        boolean autoLanguage
    ) {
        Intent intent =
            buildRecognitionIntent(language, preferOffline, partialResults);

        if (Build.VERSION.SDK_INT >= 33) {
            intent.putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY
            );
            intent.putExtra(
                RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION,
                true
            );
        }

        if (autoLanguage && Build.VERSION.SDK_INT >= 34) {
            ArrayList<String> allowed =
                new ArrayList<>(Arrays.asList("ru-RU", "es-US"));

            intent.putExtra(
                RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                RecognizerIntent.LANGUAGE_SWITCH_BALANCED
            );
            intent.putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                allowed
            );
            intent.putExtra(
                RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,
                true
            );
            intent.putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                allowed
            );
        }

        return intent;
    }

    private static boolean containsLanguage(List<String> values, String language) {
        if (values == null || language == null) return false;
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(language)) return true;
        }
        return false;
    }

    private static String recognitionErrorName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "ERROR_SERVER_DISCONNECTED";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "ERROR_LANGUAGE_NOT_SUPPORTED";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "ERROR_LANGUAGE_UNAVAILABLE";
            case SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT:
                return "ERROR_CANNOT_CHECK_SUPPORT";
            case SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS:
                return "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS";
            default: return "ERROR_" + code;
        }
    }

    private static String getConfiguredLanguage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
    }

    private static String getLanguageMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_MODE, MODE_AUTO);
    }

    private static void setActiveLanguage(Context context, String language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply();
    }

    private static String normalizeAutoDetectedLanguage(String raw) {
        if (raw == null) return null;
        String v = raw.trim().replace('_', '-');
        if (v.equalsIgnoreCase("ru") || v.regionMatches(true, 0, "ru-", 0, 3)) {
            return "ru-RU";
        }
        if (v.equalsIgnoreCase("es") || v.regionMatches(true, 0, "es-", 0, 3)) {
            return "es-US";
        }
        return null;
    }

    private static String languageSwitchResultName(int value) {
        if (Build.VERSION.SDK_INT < 34) return "UNAVAILABLE";
        switch (value) {
            case SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED:
                return "SUCCEEDED";
            case SpeechRecognizer.LANGUAGE_SWITCH_RESULT_FAILED:
                return "FAILED";
            case SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL:
                return "SKIPPED_NO_MODEL";
            case SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED:
            default:
                return "NOT_ATTEMPTED";
        }
    }

    private static String languageConfidenceName(int value) {
        if (Build.VERSION.SDK_INT < 34) return "UNAVAILABLE";
        switch (value) {
            case SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT:
                return "HIGHLY_CONFIDENT";
            case SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT:
                return "CONFIDENT";
            case SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT:
                return "NOT_CONFIDENT";
            case SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN:
            default:
                return "UNKNOWN";
        }
    }

    private static String normalizeLanguage(String raw) {
        if (raw == null) return null;
        String v = raw.trim().replace('_', '-');
        if (v.equalsIgnoreCase("ru") || v.equalsIgnoreCase("ru-RU")) return "ru-RU";
        if (v.equalsIgnoreCase("es") || v.equalsIgnoreCase("es-US")) return "es-US";
        if (v.equalsIgnoreCase("es-419")) return "es-419";
        if (v.equalsIgnoreCase("es-AR")) return "es-AR";
        if (v.equalsIgnoreCase("es-ES")) return "es-ES";
        return null;
    }

    private static final class AndroidSpeechHandler implements InvocationHandler {
        private final Context app;
        private final ClassLoader cl;

        private final Class<?> mutableStateFlowClass;
        private final Method setFlowValue;

        private final Class<?> recognitionStateClass;
        private final Constructor<?> recognitionResultCtor;
        private final Constructor<?> recognitionErrorCtor;

        private final Object initializedFlow;
        private final Object stateFlow;
        private final Object resultFlow;
        private final Object errorFlow;
        private final Object volumeFlow;

        private final Object unitInstance;

        private SpeechRecognizer recognizer;
        private boolean recognizing;
        private boolean allowContinuation;
        private boolean continuationArmed;
        private boolean continuationSpeechActive;
        private boolean continuationCandidateSpeech;
        private long continuationCandidateStartedAtMs;
        private long continuationGeneration;
        private long lastKeepAliveAtMs;
        private boolean keepAliveToggle;
        private boolean activePartialResults;
        private final StringBuilder turnBuffer = new StringBuilder();

        private long ttsGateGeneration;
        private boolean ttsGateWaiting;
        private boolean ttsGateObservedSpeaking;
        private boolean ttsGateCoordinationObserved;
        private boolean ttsGateWakeInitial;
        private long ttsGateStartedAtMs;
        private long ttsGateIdleSinceMs;
        private long ttsGateCoordSeq;
        private String ttsGateCoordState = "";
        private Object ttsGateVoiceService;
        private Object lastFloatingChatService;
        private boolean wakeInitialConsumedForService;
        private long ttsGateChecks;
        private long ttsGateDeferrals;
        private long ttsGateObservedSpeakingCount;
        private long ttsGateCoordinationObservedCount;
        private long ttsGateCoordinationPendingDeferrals;
        private long ttsGateWakeInitialCount;
        private long ttsGateWakeDiscoveryDeferrals;
        private long ttsGateTimeouts;
        private long ttsGateStartCount;
        private long ttsGateTotalWaitMs;
        private long ttsGateMaxWaitMs;
        private long lastTtsGateWaitMs;

        private boolean suppressExpectedClientError;
        private long suppressClientErrorGeneration;
        private long suppressedExpectedClientErrors;
        private String currentStateName = "UNINITIALIZED";
        private String lastRequestedLanguage = "";
        private String lastActualLanguage = "";
        private String lastLanguageMode = MODE_AUTO;
        private boolean lastAutoSwitchRequested;
        private String lastDetectedLanguageRaw = "";
        private String lastDetectedLanguage = "";
        private int lastLanguageDetectionConfidence =
            Build.VERSION.SDK_INT >= 34
                ? SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN
                : 0;
        private int lastLanguageSwitchResult =
            Build.VERSION.SDK_INT >= 34
                ? SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED
                : 0;
        private long autoSwitchRequestedCount = 0L;
        private long languageDetectionCallbacks = 0L;
        private long acceptedLanguageUpdates = 0L;
        private long rejectedLanguageDetections = 0L;
        private String lastFinalText = "";
        private String lastPartialText = "";
        private int lastErrorCode = 0;
        private String lastErrorMessage = "";
        private long recognitionStarts = 0L;
        private long finalResults = 0L;
        private long androidStartListeningCalls = 0L;
        private long continuationRestarts = 0L;
        private long continuationBegins = 0L;
        private long continuationCandidateBegins = 0L;
        private long continuationLexicalConfirms = 0L;
        private long continuationNoiseRejected = 0L;
        private long continuationLexicalGraceExtensions = 0L;
        private long continuationReleaseTimeouts = 0L;
        private long suppressedContinuationEndErrors = 0L;
        private long keepAlivePublishes = 0L;

        AndroidSpeechHandler(Context app, ClassLoader cl) throws Exception {
            this.app = app;
            this.cl = cl;

            Class<?> stateFlowKt = Class.forName(STATE_FLOW_KT, false, cl);
            this.mutableStateFlowClass = Class.forName(MUTABLE_STATE_FLOW, false, cl);
            Method mutableStateFlowFactory =
                stateFlowKt.getMethod("MutableStateFlow", Object.class);
            this.setFlowValue =
                mutableStateFlowClass.getMethod("setValue", Object.class);

            this.recognitionStateClass =
                Class.forName(RECOGNITION_STATE, false, cl);
            Class<?> resultClass = Class.forName(RECOGNITION_RESULT, false, cl);
            Class<?> errorClass = Class.forName(RECOGNITION_ERROR, false, cl);

            this.recognitionResultCtor =
                findConstructor(resultClass, String.class, boolean.class, float.class);
            this.recognitionErrorCtor =
                findConstructor(errorClass, int.class, String.class);

            this.unitInstance =
                Class.forName("kotlin.Unit", false, cl).getField("INSTANCE").get(null);

            this.initializedFlow = mutableStateFlowFactory.invoke(null, Boolean.FALSE);
            this.stateFlow =
                mutableStateFlowFactory.invoke(null, enumState("UNINITIALIZED"));
            this.resultFlow =
                mutableStateFlowFactory.invoke(null, newRecognitionResult("", false, 0f));
            this.errorFlow =
                mutableStateFlowFactory.invoke(null, newRecognitionError(0, ""));
            this.volumeFlow = mutableStateFlowFactory.invoke(null, Float.valueOf(0f));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if ("toString".equals(name)) return "OperitAndroidSpeechServiceAdapter";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }

            if ("getIsInitialized".equals(name)) return initializedFlow;
            if ("isRecognizing".equals(name)) return recognizing;
            if ("getCurrentState".equals(name)) return enumState(currentStateName);
            if ("getRecognitionStateFlow".equals(name)) return stateFlow;
            if ("getRecognitionResultFlow".equals(name)) return resultFlow;
            if ("getRecognitionErrorFlow".equals(name)) return errorFlow;
            if ("getVolumeLevelFlow".equals(name)) return volumeFlow;

            if ("initialize".equals(name)) {
                return Boolean.valueOf(initializeRecognizer());
            }

            if ("startRecognition".equals(name)) {
                String requestedLanguage =
                    args != null && args.length > 0 && args[0] != null
                        ? String.valueOf(args[0])
                        : "";
                boolean partial =
                    args != null && args.length > 2 && Boolean.TRUE.equals(args[2]);
                return Boolean.valueOf(startRecognition(requestedLanguage, partial));
            }

            if ("stopRecognition".equals(name)) {
                return Boolean.valueOf(stopRecognition());
            }

            if ("cancelRecognition".equals(name)) {
                cancelRecognition();
                return unitInstance;
            }

            if ("shutdown".equals(name)) {
                shutdown();
                return null;
            }

            if ("getSupportedLanguages".equals(name)) {
                return Arrays.asList("ru-RU", "es-US", "es-419", "es-AR", "es-ES");
            }

            if ("recognize".equals(name)) {
                // Pre-recorded PCM recognition is outside adapter.
                return unitInstance;
            }

            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) return false;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == float.class) return 0f;
            if (rt == double.class) return 0d;
            if ("kotlin.Unit".equals(rt.getName())) return unitInstance;
            return null;
        }

        private boolean initializeRecognizer() {
            try {
                Boolean ok = runOnMainSync(() -> {
                    ensureRecognizerOnMain();
                    setFlow(initializedFlow, Boolean.TRUE);
                    setState("IDLE");
                    return Boolean.TRUE;
                }, 2500L);
                return Boolean.TRUE.equals(ok);
            } catch (Throwable t) {
                publishError(-1001, describe(t));
                return false;
            }
        }

        private boolean startRecognition(String requestedLanguage, boolean partialResults) {
            lastRequestedLanguage = requestedLanguage == null ? "" : requestedLanguage;
            lastPartialText = "";

            String actual = getConfiguredLanguage(app);
            String mode = getLanguageMode(app);
            boolean autoLanguage =
                MODE_AUTO.equals(mode) && Build.VERSION.SDK_INT >= 34;

            lastActualLanguage = actual;
            lastLanguageMode = mode;
            lastAutoSwitchRequested = autoLanguage;
            activePartialResults = partialResults;

            allowContinuation = true;
            continuationArmed = false;
            continuationCandidateSpeech = false;
            continuationSpeechActive = false;
            continuationGeneration++;
            turnBuffer.setLength(0);
            lastKeepAliveAtMs = 0L;

            try {
                Boolean ok = runOnMainSync(() -> {
                    ensureRecognizerOnMain();

                    if (recognizing) {
                        markExpectedClientErrorFromCancel();
                        try { recognizer.cancel(); } catch (Throwable ignored) {}
                        recognizing = false;
                    }

                    beginInitialRecognizerStartWithTtsGateOnMain();
                    recognitionStarts++;
                    return Boolean.TRUE;
                }, 2500L);
                return Boolean.TRUE.equals(ok);
            } catch (Throwable t) {
                recognizing = false;
                publishError(-1002, describe(t));
                return false;
            }
        }

        private void beginInitialRecognizerStartWithTtsGateOnMain() throws Exception {
            final long generation = ++ttsGateGeneration;

            ttsGateWaiting = true;
            ttsGateObservedSpeaking = false;
            ttsGateCoordinationObserved = false;
            ttsGateWakeInitial = isWakeInitialRecognition();
            if (ttsGateWakeInitial) ttsGateWakeInitialCount++;

            ttsGateStartedAtMs = SystemClock.elapsedRealtime();
            ttsGateIdleSinceMs = 0L;
            ttsGateCoordSeq = 0L;
            ttsGateCoordState = "";
            ttsGateVoiceService = getCurrentVoiceServiceQuiet();

            checkTtsGateAndStart(generation);
        }

        private void checkTtsGateAndStart(final long generation) throws Exception {
            if (!allowContinuation || generation != ttsGateGeneration) {
                ttsGateWaiting = false;
                return;
            }

            final long now = SystemClock.elapsedRealtime();
            final long waited = now - ttsGateStartedAtMs;
            ttsGateChecks++;

            VoiceActivityState voice = readVoiceActivityState(ttsGateVoiceService);
            DuplexCoordState coord = readDuplexCoordState(now);

            if (coord.fresh && coord.seq > 0L) {
                if (!ttsGateCoordinationObserved
                    || coord.seq != ttsGateCoordSeq) {
                    ttsGateCoordinationObservedCount++;
                }
                ttsGateCoordinationObserved = true;
                ttsGateCoordSeq = coord.seq;
                ttsGateCoordState = coord.state;
            }

            if (voice.speaking) {
                if (!ttsGateObservedSpeaking) {
                    ttsGateObservedSpeaking = true;
                    ttsGateObservedSpeakingCount++;
                }
                ttsGateIdleSinceMs = 0L;
                ttsGateDeferrals++;
                scheduleTtsGateCheck(generation);
                return;
            }

            if (coord.pending) {
                ttsGateCoordinationPendingDeferrals++;
                ttsGateIdleSinceMs = 0L;
                ttsGateDeferrals++;
                scheduleTtsGateCheck(generation);
                return;
            }

            if (ttsGateWakeInitial
                && !ttsGateCoordinationObserved
                && !ttsGateObservedSpeaking
                && waited < TTS_GATE_WAKE_DISCOVERY_MS) {
                // On wake, SpeechInteractionManager launches greeting TTS in one
                // coroutine and STT in another. Give the VoiceService adapter time
                // to publish REQUESTED even if TTS initialization is still pending.
                ttsGateWakeDiscoveryDeferrals++;
                ttsGateDeferrals++;
                scheduleTtsGateCheck(generation);
                return;
            }

            boolean hadTtsOwnership =
                ttsGateObservedSpeaking || ttsGateCoordinationObserved;

            if (hadTtsOwnership) {
                if (ttsGateIdleSinceMs == 0L) {
                    ttsGateIdleSinceMs = now;
                    ttsGateDeferrals++;
                    scheduleTtsGateCheck(generation);
                    return;
                }

                if (now - ttsGateIdleSinceMs < TTS_GATE_IDLE_TAIL_MS) {
                    ttsGateDeferrals++;
                    scheduleTtsGateCheck(generation);
                    return;
                }
            }

            finishTtsGateAndStart(waited, false);
        }

        private void finishTtsGateAndStart(long waited, boolean timedOut)
            throws Exception {
            if (timedOut) ttsGateTimeouts++;

            ttsGateWaiting = false;
            lastTtsGateWaitMs = waited;
            ttsGateTotalWaitMs += waited;
            if (waited > ttsGateMaxWaitMs) ttsGateMaxWaitMs = waited;
            ttsGateStartCount++;

            startRecognizerSessionOnMain(false);
        }

        private void scheduleTtsGateCheck(final long generation) {
            final long now = SystemClock.elapsedRealtime();
            final long waited = now - ttsGateStartedAtMs;

            if (waited >= TTS_GATE_MAX_WAIT_MS) {
                try {
                    finishTtsGateAndStart(waited, true);
                } catch (Throwable t) {
                    recognizing = false;
                    publishError(-1006, describe(t));
                }
                return;
            }

            MAIN.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            checkTtsGateAndStart(generation);
                        } catch (Throwable t) {
                            ttsGateWaiting = false;
                            recognizing = false;
                            publishError(-1006, describe(t));
                        }
                    }
                },
                TTS_GATE_POLL_MS
            );
        }

        private DuplexCoordState readDuplexCoordState(long nowElapsed) {
            try {
                SharedPreferences prefs =
                    app.getSharedPreferences(DUPLEX_PREFS, Context.MODE_PRIVATE);
                long seq = prefs.getLong(DUPLEX_KEY_SEQ, 0L);
                String state = prefs.getString(DUPLEX_KEY_STATE, "");
                long requestAt = prefs.getLong(DUPLEX_KEY_REQUEST_AT, 0L);

                long age =
                    requestAt <= 0L
                        ? Long.MAX_VALUE
                        : nowElapsed - requestAt;
                boolean fresh = age >= 0L && age <= DUPLEX_COORD_FRESH_MS;
                boolean pending =
                    fresh
                        && ("REQUESTED".equals(state)
                            || "SPEAKING".equals(state));

                return new DuplexCoordState(
                    seq,
                    state == null ? "" : state,
                    requestAt,
                    fresh,
                    pending
                );
            } catch (Throwable ignored) {
                return new DuplexCoordState(0L, "", 0L, false, false);
            }
        }

        private boolean isWakeInitialRecognition() {
            try {
                Class<?> serviceClass =
                    Class.forName(
                        "com.ai.assistance.operit.services.FloatingChatService",
                        false,
                        cl
                    );
                Field companionField = serviceClass.getField("Companion");
                Object companion = companionField.get(null);
                if (companion == null) return false;

                Method getInstance =
                    companion.getClass().getMethod("getInstance");
                Object service = getInstance.invoke(companion);

                if (service == null) {
                    lastFloatingChatService = null;
                    wakeInitialConsumedForService = false;
                    return false;
                }

                if (service != lastFloatingChatService) {
                    lastFloatingChatService = service;
                    wakeInitialConsumedForService = false;
                }

                Method isWakeLaunched =
                    service.getClass().getMethod("isWakeLaunched");
                Object value = isWakeLaunched.invoke(service);
                boolean wake = value instanceof Boolean
                    && ((Boolean) value).booleanValue();

                if (wake && !wakeInitialConsumedForService) {
                    wakeInitialConsumedForService = true;
                    return true;
                }
            } catch (Throwable ignored) {
            }

            return false;
        }

        private static final class DuplexCoordState {
            final long seq;
            final String state;
            final long requestAt;
            final boolean fresh;
            final boolean pending;

            DuplexCoordState(
                long seq,
                String state,
                long requestAt,
                boolean fresh,
                boolean pending
            ) {
                this.seq = seq;
                this.state = state;
                this.requestAt = requestAt;
                this.fresh = fresh;
                this.pending = pending;
            }
        }

        private Object getCurrentVoiceServiceQuiet() {
            try {
                Class<?> factoryClass = Class.forName(VOICE_FACTORY, false, cl);
                Field voiceInstanceField = factoryClass.getDeclaredField("instance");
                voiceInstanceField.setAccessible(true);

                Object current = voiceInstanceField.get(null);
                if (current != null) return current;

                Object singleton = factoryClass.getField("INSTANCE").get(null);
                Method getInstance =
                    factoryClass.getMethod("getInstance", Context.class);
                return getInstance.invoke(singleton, app);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private VoiceActivityState readVoiceActivityState(Object voiceService) {
            if (voiceService == null) {
                return new VoiceActivityState(true, false);
            }

            boolean initialized =
                readBooleanNoArgs(
                    voiceService,
                    true,
                    "isInitialized",
                    "getIsInitialized"
                );
            boolean speaking =
                readBooleanNoArgs(
                    voiceService,
                    false,
                    "isSpeaking",
                    "getIsSpeaking"
                );

            return new VoiceActivityState(initialized, speaking);
        }

        private boolean readBooleanNoArgs(
            Object target,
            boolean fallback,
            String... names
        ) {
            if (target == null) return fallback;

            for (String name : names) {
                try {
                    Method m = target.getClass().getMethod(name);
                    m.setAccessible(true);
                    Object value = m.invoke(target);
                    if (value instanceof Boolean) {
                        return ((Boolean) value).booleanValue();
                    }
                } catch (Throwable ignored) {
                }

                try {
                    Method m = target.getClass().getDeclaredMethod(name);
                    m.setAccessible(true);
                    Object value = m.invoke(target);
                    if (value instanceof Boolean) {
                        return ((Boolean) value).booleanValue();
                    }
                } catch (Throwable ignored) {
                }
            }

            return fallback;
        }

        private static final class VoiceActivityState {
            final boolean initialized;
            final boolean speaking;

            VoiceActivityState(boolean initialized, boolean speaking) {
                this.initialized = initialized;
                this.speaking = speaking;
            }
        }

        private void startRecognizerSessionOnMain(boolean continuation) throws Exception {
            String actual = getConfiguredLanguage(app);
            String mode = getLanguageMode(app);
            boolean autoLanguage =
                MODE_AUTO.equals(mode) && Build.VERSION.SDK_INT >= 34;

            lastActualLanguage = actual;
            lastLanguageMode = mode;
            lastAutoSwitchRequested = autoLanguage;

            Intent intent =
                buildRuntimeRecognitionIntent(
                    actual,
                    true,
                    activePartialResults,
                    autoLanguage
                );

            setState("PREPARING");
            recognizing = true;
            androidStartListeningCalls++;
            if (autoLanguage) autoSwitchRequestedCount++;
            if (continuation) continuationRestarts++;
            recognizer.startListening(intent);

            if (continuation) {
                armContinuationRelease();
            }
        }

        private void scheduleContinuationRestart() {
            if (!allowContinuation) {
                setStateQuiet("IDLE");
                return;
            }

            final long generation = ++continuationGeneration;
            MAIN.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!allowContinuation || continuationGeneration != generation) {
                            return;
                        }
                        try {
                            startRecognizerSessionOnMain(true);
                        } catch (Throwable t) {
                            recognizing = false;
                            publishError(-1005, describe(t));
                        }
                    }
                },
                CONTINUATION_RESTART_DELAY_MS
            );
        }

        private void armContinuationRelease() {
            continuationArmed = true;
            continuationSpeechActive = false;
            continuationCandidateSpeech = false;
            continuationCandidateStartedAtMs = 0L;

            final long generation = ++continuationGeneration;
            scheduleContinuationReleaseCheck(
                generation,
                SystemClock.elapsedRealtime() + CONTINUATION_HOLD_MS
            );
        }

        private void scheduleContinuationReleaseCheck(
            final long generation,
            final long deadlineMs
        ) {
            long delayMs =
                Math.max(
                    0L,
                    deadlineMs - SystemClock.elapsedRealtime()
                );

            MAIN.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (!allowContinuation
                            || !continuationArmed
                            || continuationGeneration != generation) {
                            return;
                        }

                        long now = SystemClock.elapsedRealtime();

                        if (continuationCandidateSpeech
                            && continuationCandidateStartedAtMs > 0L) {
                            long candidateAge =
                                now - continuationCandidateStartedAtMs;

                            if (candidateAge >= 0L
                                && candidateAge < CONTINUATION_LEXICAL_GRACE_MS) {
                                continuationLexicalGraceExtensions++;
                                scheduleContinuationReleaseCheck(
                                    generation,
                                    continuationCandidateStartedAtMs
                                        + CONTINUATION_LEXICAL_GRACE_MS
                                );
                                return;
                            }
                        }

                        if (continuationCandidateSpeech) {
                            continuationNoiseRejected++;
                        }

                        continuationArmed = false;
                        continuationCandidateSpeech = false;
                        continuationSpeechActive = false;
                        continuationReleaseTimeouts++;

                        if (recognizer != null && recognizing) {
                            markExpectedClientErrorFromCancel();
                            try { recognizer.cancel(); } catch (Throwable ignored) {}
                        }

                        recognizing = false;
                        setFlowQuiet(volumeFlow, Float.valueOf(0f));
                        setStateQuiet("IDLE");
                    }
                },
                delayMs
            );
        }

        private void noteContinuationSpeechCandidate() {
            if (turnBuffer.length() == 0 || !continuationArmed) return;

            if (!continuationCandidateSpeech) {
                continuationCandidateSpeech = true;
                continuationCandidateStartedAtMs = SystemClock.elapsedRealtime();
                continuationCandidateBegins++;
            }
        }

        private void confirmContinuationLexical() {
            if (turnBuffer.length() == 0) return;

            if (continuationArmed) {
                continuationArmed = false;
                continuationCandidateSpeech = false;
                continuationGeneration++;
                continuationBegins++;
                continuationLexicalConfirms++;
            }

            continuationSpeechActive = true;
            publishStableKeepAlive(true);
        }

        private void publishStableKeepAlive(boolean force) {
            if (turnBuffer.length() == 0) return;

            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastKeepAliveAtMs < KEEPALIVE_MIN_INTERVAL_MS) {
                return;
            }

            lastKeepAliveAtMs = now;
            keepAliveToggle = !keepAliveToggle;
            float confidence = keepAliveToggle ? 0.91f : 0.92f;

            setFlowQuiet(
                resultFlow,
                newRecognitionResultQuiet(turnBuffer.toString(), false, confidence)
            );
            keepAlivePublishes++;
        }

        private String appendStableSegment(String segment) {
            String clean = segment == null ? "" : segment.trim();
            if (clean.isEmpty()) return turnBuffer.toString();

            if (turnBuffer.length() > 0) {
                char last = turnBuffer.charAt(turnBuffer.length() - 1);
                char first = clean.charAt(0);

                if (!Character.isWhitespace(last)
                    && !isLeadingPunctuation(first)) {
                    turnBuffer.append(' ');
                }
            }

            turnBuffer.append(clean);
            return turnBuffer.toString();
        }

        private boolean isLeadingPunctuation(char c) {
            return c == ',' || c == '.' || c == ':' || c == ';'
                || c == '!' || c == '?' || c == ')' || c == ']'
                || c == '}' || c == '，' || c == '。' || c == '：'
                || c == '；' || c == '！' || c == '？';
        }

        private boolean stopRecognition() {
            allowContinuation = false;
            continuationArmed = false;
            continuationCandidateSpeech = false;
            continuationSpeechActive = false;
            continuationGeneration++;
            ttsGateWaiting = false;
            ttsGateGeneration++;

            try {
                Boolean ok = runOnMainSync(() -> {
                    if (recognizer != null && recognizing) {
                        setState("PROCESSING");
                        recognizer.stopListening();
                    }
                    return Boolean.TRUE;
                }, 2000L);
                return Boolean.TRUE.equals(ok);
            } catch (Throwable t) {
                publishError(-1003, describe(t));
                return false;
            }
        }

        private void cancelRecognition() {
            allowContinuation = false;
            continuationArmed = false;
            continuationSpeechActive = false;
            continuationGeneration++;
            ttsGateWaiting = false;
            ttsGateGeneration++;
            turnBuffer.setLength(0);

            try {
                runOnMainSync(() -> {
                    if (recognizer != null) {
                        markExpectedClientErrorFromCancel();
                        try { recognizer.cancel(); } catch (Throwable ignored) {}
                    }
                    recognizing = false;
                    setFlow(volumeFlow, Float.valueOf(0f));
                    setState("IDLE");
                    return Boolean.TRUE;
                }, 2000L);
            } catch (Throwable t) {
                publishError(-1004, describe(t));
            }
        }

        void shutdown() {
            allowContinuation = false;
            continuationArmed = false;
            continuationSpeechActive = false;
            continuationGeneration++;
            ttsGateWaiting = false;
            ttsGateGeneration++;
            turnBuffer.setLength(0);

            try {
                runOnMainSync(() -> {
                    recognizing = false;
                    if (recognizer != null) {
                        markExpectedClientErrorFromCancel();
                        try { recognizer.cancel(); } catch (Throwable ignored) {}
                        try { recognizer.destroy(); } catch (Throwable ignored) {}
                        recognizer = null;
                    }
                    setFlow(volumeFlow, Float.valueOf(0f));
                    setFlow(initializedFlow, Boolean.FALSE);
                    setState("UNINITIALIZED");
                    return Boolean.TRUE;
                }, 2000L);
            } catch (Throwable ignored) {
            }
        }

        void noteConfiguredLanguage(String language, String mode) {
            // Status-only hint. The next startRecognition reads preferences again.
            lastActualLanguage = language;
            lastLanguageMode = mode;
        }

        void appendStatus(JSONObject o) throws Exception {
            o.put("currentState", currentStateName);
            o.put("recognizing", recognizing);
            o.put("lastRequestedLanguageFromOperit", lastRequestedLanguage);
            o.put("lastActualLanguage", lastActualLanguage);
            o.put("lastLanguageMode", lastLanguageMode);
            o.put("lastAutoSwitchRequested", lastAutoSwitchRequested);
            o.put("lastDetectedLanguageRaw", lastDetectedLanguageRaw);
            o.put("lastDetectedLanguage", lastDetectedLanguage);
            o.put("lastLanguageDetectionConfidence", lastLanguageDetectionConfidence);
            o.put(
                "lastLanguageDetectionConfidenceName",
                languageConfidenceName(lastLanguageDetectionConfidence)
            );
            o.put("lastLanguageSwitchResult", lastLanguageSwitchResult);
            o.put(
                "lastLanguageSwitchResultName",
                languageSwitchResultName(lastLanguageSwitchResult)
            );
            o.put("autoSwitchRequestedCount", autoSwitchRequestedCount);
            o.put("languageDetectionCallbacks", languageDetectionCallbacks);
            o.put("acceptedLanguageUpdates", acceptedLanguageUpdates);
            o.put("rejectedLanguageDetections", rejectedLanguageDetections);
            o.put("lastPartialText", lastPartialText);
            o.put("lastFinalText", lastFinalText);
            o.put("lastErrorCode", lastErrorCode);
            o.put("lastErrorMessage", lastErrorMessage);
            o.put("recognitionStarts", recognitionStarts);
            o.put("finalResults", finalResults);
            o.put("androidStartListeningCalls", androidStartListeningCalls);
            o.put("continuationEnabled", true);
            o.put("continuationHoldMs", CONTINUATION_HOLD_MS);
            o.put("ttsDuplexGateEnabled", true);
            o.put("ttsGateWaiting", ttsGateWaiting);
            o.put("ttsGateWakeInitial", ttsGateWakeInitial);
            o.put("ttsGateWakeDiscoveryMs", TTS_GATE_WAKE_DISCOVERY_MS);
            o.put("ttsGateIdleTailMs", TTS_GATE_IDLE_TAIL_MS);
            o.put("ttsGateChecks", ttsGateChecks);
            o.put("ttsGateDeferrals", ttsGateDeferrals);
            o.put("ttsGateObservedSpeakingCount", ttsGateObservedSpeakingCount);
            o.put("ttsGateCoordinationObserved", ttsGateCoordinationObserved);
            o.put("ttsGateCoordSeq", ttsGateCoordSeq);
            o.put("ttsGateCoordState", ttsGateCoordState);
            o.put("ttsGateCoordinationObservedCount", ttsGateCoordinationObservedCount);
            o.put("ttsGateCoordinationPendingDeferrals", ttsGateCoordinationPendingDeferrals);
            o.put("ttsGateWakeInitialCount", ttsGateWakeInitialCount);
            o.put("ttsGateWakeDiscoveryDeferrals", ttsGateWakeDiscoveryDeferrals);
            o.put("ttsGateTimeouts", ttsGateTimeouts);
            o.put("ttsGateStartCount", ttsGateStartCount);
            o.put("lastTtsGateWaitMs", lastTtsGateWaitMs);
            o.put("ttsGateTotalWaitMs", ttsGateTotalWaitMs);
            o.put("ttsGateMaxWaitMs", ttsGateMaxWaitMs);
            o.put("continuationRestartDelayMs", CONTINUATION_RESTART_DELAY_MS);
            o.put("continuationLexicalGraceMs", CONTINUATION_LEXICAL_GRACE_MS);
            o.put("continuationRestarts", continuationRestarts);
            o.put("continuationBegins", continuationBegins);
            o.put("continuationCandidateBegins", continuationCandidateBegins);
            o.put("continuationLexicalConfirms", continuationLexicalConfirms);
            o.put("continuationNoiseRejected", continuationNoiseRejected);
            o.put("continuationLexicalGraceExtensions", continuationLexicalGraceExtensions);
            o.put("continuationReleaseTimeouts", continuationReleaseTimeouts);
            o.put("suppressedContinuationEndErrors", suppressedContinuationEndErrors);
            o.put("keepAlivePublishes", keepAlivePublishes);
            o.put("turnBuffer", turnBuffer.toString());
            o.put("formattingRequested", Build.VERSION.SDK_INT >= 33);
            o.put("formattingMode", Build.VERSION.SDK_INT >= 33 ? "QUALITY" : "UNAVAILABLE");
            o.put("suppressedExpectedClientErrors", suppressedExpectedClientErrors);
        }

        private void ensureRecognizerOnMain() throws Exception {
            if (recognizer != null) return;
            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                throw new IllegalStateException(
                    "Android SpeechRecognizer is not available"
                );
            }

            recognizer = SpeechRecognizer.createSpeechRecognizer(app);
            recognizer.setRecognitionListener(
                new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        recognizing = true;
                        setStateQuiet("RECOGNIZING");
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                        recognizing = true;
                        noteContinuationSpeechCandidate();
                        setStateQuiet("RECOGNIZING");
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                        float normalized = (rmsdB + 2f) / 12f;
                        if (normalized < 0f) normalized = 0f;
                        if (normalized > 1f) normalized = 1f;
                        setFlowQuiet(volumeFlow, Float.valueOf(normalized));

                        if (continuationSpeechActive) {
                            publishStableKeepAlive(false);
                        }
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {
                        setStateQuiet("PROCESSING");
                    }

                    @Override
                    public void onError(int error) {
                        if (error == SpeechRecognizer.ERROR_CLIENT
                            && consumeExpectedClientErrorFromCancel()) {
                            suppressedExpectedClientErrors++;
                            return;
                        }

                        if (turnBuffer.length() > 0
                            && allowContinuation
                            && (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                            suppressedContinuationEndErrors++;
                            recognizing = false;
                            continuationArmed = false;
                            continuationCandidateSpeech = false;
                            continuationSpeechActive = false;
                            continuationGeneration++;
                            setFlowQuiet(volumeFlow, Float.valueOf(0f));
                            setStateQuiet("IDLE");
                            return;
                        }

                        recognizing = false;
                        continuationArmed = false;
                        continuationCandidateSpeech = false;
                        continuationSpeechActive = false;
                        setFlowQuiet(volumeFlow, Float.valueOf(0f));
                        publishError(error, recognitionErrorMessage(error));
                    }

                    @Override
                    public void onLanguageDetection(Bundle results) {
                        if (Build.VERSION.SDK_INT < 34 || results == null) return;

                        languageDetectionCallbacks++;

                        String raw =
                            results.getString(SpeechRecognizer.DETECTED_LANGUAGE, "");
                        int confidence =
                            results.getInt(
                                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN
                            );
                        int switchResult =
                            results.getInt(
                                SpeechRecognizer.LANGUAGE_SWITCH_RESULT,
                                SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED
                            );

                        String normalized = normalizeAutoDetectedLanguage(raw);

                        lastDetectedLanguageRaw = raw == null ? "" : raw;
                        lastDetectedLanguage = normalized == null ? "" : normalized;
                        lastLanguageDetectionConfidence = confidence;
                        lastLanguageSwitchResult = switchResult;

                        if (!MODE_AUTO.equals(getLanguageMode(app))
                            || normalized == null
                            || confidence
                                < SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) {
                            rejectedLanguageDetections++;
                            return;
                        }

                        boolean switchSucceeded =
                            switchResult
                                == SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED;
                        boolean alreadyActive =
                            switchResult
                                == SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED
                            && normalized.equalsIgnoreCase(lastActualLanguage);

                        if (switchSucceeded || alreadyActive) {
                            if (!normalized.equalsIgnoreCase(lastActualLanguage)) {
                                acceptedLanguageUpdates++;
                            }
                            lastActualLanguage = normalized;
                            setActiveLanguage(app, normalized);
                        } else {
                            rejectedLanguageDetections++;
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        String text = firstResult(results);
                        recognizing = false;
                        continuationSpeechActive = false;
                        setFlowQuiet(volumeFlow, Float.valueOf(0f));

                        if (!text.isEmpty()) {
                            if (turnBuffer.length() > 0 && continuationArmed) {
                                confirmContinuationLexical();
                            }
                            String cumulative = appendStableSegment(text);
                            lastFinalText = cumulative;
                            lastErrorCode = 0;
                            lastErrorMessage = "";
                            finalResults++;
                            continuationCandidateSpeech = false;
                            continuationSpeechActive = false;
                            setFlowQuiet(
                                resultFlow,
                                newRecognitionResultQuiet(cumulative, true, 1f)
                            );
                        }

                        if (allowContinuation && turnBuffer.length() > 0) {
                            scheduleContinuationRestart();
                        } else {
                            setStateQuiet("IDLE");
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        String text = firstResult(partialResults);
                        if (!text.isEmpty()) {
                            // Vendor partials can revise earlier words, so they remain
                            // diagnostic-only. But non-empty lexical text is strong evidence
                            // that a continuation is real speech rather than breathing/noise.
                            lastPartialText = text;

                            if (turnBuffer.length() > 0 && continuationArmed) {
                                confirmContinuationLexical();
                            } else if (
                                continuationSpeechActive
                                    && turnBuffer.length() > 0
                            ) {
                                publishStableKeepAlive(false);
                            }
                        }
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {}
                }
            );
        }

        private String firstResult(Bundle bundle) {
            if (bundle == null) return "";
            List<String> list =
                bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty() || list.get(0) == null) return "";
            return list.get(0).trim();
        }

        private void publishError(int code, String message) {
            lastErrorCode = code;
            lastErrorMessage = message == null ? "" : message;
            setFlowQuiet(
                errorFlow,
                newRecognitionErrorQuiet(code, lastErrorMessage)
            );
            setStateQuiet("ERROR");
        }

        private void markExpectedClientErrorFromCancel() {
            suppressExpectedClientError = true;
            final long generation = ++suppressClientErrorGeneration;

            MAIN.postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        if (suppressClientErrorGeneration == generation) {
                            suppressExpectedClientError = false;
                        }
                    }
                },
                2000L
            );
        }

        private boolean consumeExpectedClientErrorFromCancel() {
            if (!suppressExpectedClientError) return false;
            suppressExpectedClientError = false;
            return true;
        }

        private String recognitionErrorMessage(int code) {
            return recognitionErrorName(code);
        }

        private Object enumState(String name) throws Exception {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object v = Enum.valueOf((Class<? extends Enum>) recognitionStateClass, name);
            return v;
        }

        private void setState(String name) throws Exception {
            currentStateName = name;
            setFlow(stateFlow, enumState(name));
        }

        private void setStateQuiet(String name) {
            try {
                setState(name);
            } catch (Throwable ignored) {
            }
        }

        private Object newRecognitionResult(String text, boolean isFinal, float confidence)
            throws Exception {
            return recognitionResultCtor.newInstance(text, isFinal, confidence);
        }

        private Object newRecognitionResultQuiet(
            String text,
            boolean isFinal,
            float confidence
        ) {
            try {
                return newRecognitionResult(text, isFinal, confidence);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object newRecognitionError(int code, String message)
            throws Exception {
            return recognitionErrorCtor.newInstance(code, message);
        }

        private Object newRecognitionErrorQuiet(int code, String message) {
            try {
                return newRecognitionError(code, message);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void setFlow(Object flow, Object value) throws Exception {
            if (value != null) setFlowValue.invoke(flow, value);
        }

        private void setFlowQuiet(Object flow, Object value) {
            try {
                setFlow(flow, value);
            } catch (Throwable ignored) {
            }
        }
    }

    private interface MainCallable<T> {
        T call() throws Exception;
    }

    private static <T> T runOnMainSync(MainCallable<T> callable, long timeoutMs)
        throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callable.call();
        }

        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        MAIN.post(() -> {
            try {
                value.set(callable.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out waiting for Android main thread");
        }

        Throwable t = error.get();
        if (t != null) {
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        return value.get();
    }

    private static Constructor<?> findConstructor(
        Class<?> type,
        Class<?>... params
    ) throws Exception {
        Constructor<?> c = type.getDeclaredConstructor(params);
        c.setAccessible(true);
        return c;
    }

    private static String describe(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getName()
            + (msg == null || msg.trim().isEmpty() ? "" : ": " + msg);
    }
}
