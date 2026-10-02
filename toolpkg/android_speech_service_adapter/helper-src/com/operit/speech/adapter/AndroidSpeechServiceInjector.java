package com.operit.speech.adapter;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final String DEFAULT_LANGUAGE = "ru-RU";

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
            .apply();
        if (handler != null) handler.noteConfiguredLanguage(normalized);
        return true;
    }

    public static String getStatusJson(Context context) {
        JSONObject o = new JSONObject();
        try {
            Context a = context.getApplicationContext();
            String configured = getConfiguredLanguage(a);
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
                o.put("configuredLocales", "ru-RU,es-AR,es-ES");
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

    private static String normalizeLanguage(String raw) {
        if (raw == null) return null;
        String v = raw.trim().replace('_', '-');
        if (v.equalsIgnoreCase("ru") || v.equalsIgnoreCase("ru-RU")) return "ru-RU";
        if (v.equalsIgnoreCase("es") || v.equalsIgnoreCase("es-AR")) return "es-AR";
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
        private String currentStateName = "UNINITIALIZED";
        private String lastRequestedLanguage = "";
        private String lastActualLanguage = "";
        private String lastFinalText = "";
        private String lastPartialText = "";
        private int lastErrorCode = 0;
        private String lastErrorMessage = "";
        private long recognitionStarts = 0L;
        private long finalResults = 0L;

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
                return Arrays.asList("ru-RU", "es-AR", "es-ES");
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
            lastActualLanguage = actual;

            try {
                Boolean ok = runOnMainSync(() -> {
                    ensureRecognizerOnMain();

                    if (recognizing) {
                        try { recognizer.cancel(); } catch (Throwable ignored) {}
                        recognizing = false;
                    }

                    Intent intent =
                        buildRecognitionIntent(actual, true, partialResults);

                    setState("PREPARING");
                    recognizing = true;
                    recognitionStarts++;
                    recognizer.startListening(intent);
                    return Boolean.TRUE;
                }, 2500L);
                return Boolean.TRUE.equals(ok);
            } catch (Throwable t) {
                recognizing = false;
                publishError(-1002, describe(t));
                return false;
            }
        }

        private boolean stopRecognition() {
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
            try {
                runOnMainSync(() -> {
                    if (recognizer != null) {
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
            try {
                runOnMainSync(() -> {
                    recognizing = false;
                    if (recognizer != null) {
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

        void noteConfiguredLanguage(String language) {
            // Status-only hint. The next startRecognition reads preferences again.
            lastActualLanguage = language;
        }

        void appendStatus(JSONObject o) throws Exception {
            o.put("currentState", currentStateName);
            o.put("recognizing", recognizing);
            o.put("lastRequestedLanguageFromOperit", lastRequestedLanguage);
            o.put("lastActualLanguage", lastActualLanguage);
            o.put("lastPartialText", lastPartialText);
            o.put("lastFinalText", lastFinalText);
            o.put("lastErrorCode", lastErrorCode);
            o.put("lastErrorMessage", lastErrorMessage);
            o.put("recognitionStarts", recognitionStarts);
            o.put("finalResults", finalResults);
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
                        setStateQuiet("RECOGNIZING");
                    }

                    @Override
                    public void onRmsChanged(float rmsdB) {
                        float normalized = (rmsdB + 2f) / 12f;
                        if (normalized < 0f) normalized = 0f;
                        if (normalized > 1f) normalized = 1f;
                        setFlowQuiet(volumeFlow, Float.valueOf(normalized));
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {
                        setStateQuiet("PROCESSING");
                    }

                    @Override
                    public void onError(int error) {
                        recognizing = false;
                        setFlowQuiet(volumeFlow, Float.valueOf(0f));
                        publishError(error, recognitionErrorMessage(error));
                    }

                    @Override
                    public void onResults(Bundle results) {
                        String text = firstResult(results);
                        recognizing = false;
                        setFlowQuiet(volumeFlow, Float.valueOf(0f));
                        if (!text.isEmpty()) {
                            lastFinalText = text;
                            finalResults++;
                            setFlowQuiet(
                                resultFlow,
                                newRecognitionResultQuiet(text, true, 1f)
                            );
                        }
                        setStateQuiet("IDLE");
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        String text = firstResult(partialResults);
                        if (!text.isEmpty()) {
                            // Android SpeechRecognizer revises partial hypotheses.
                            // Operit's stock SpeechInteractionManager assumes mostly
                            // monotonic partials and otherwise appends the previous
                            // hypothesis into accumulatedText. Keep the latest partial
                            // only for diagnostics and publish only the final result.
                            lastPartialText = text;
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
