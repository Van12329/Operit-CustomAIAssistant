package com.operit.voice.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;

public final class AndroidVoiceServiceInjector {
    private static final Object LOCK = new Object();

    private static final String SPEECH_PREFS = "operit_android_speech_service_adapter";
    private static final String SPEECH_LANGUAGE_KEY = "language";
    private static final String SPEECH_LAST_FINAL_LANGUAGE_KEY = "last_final_language";
    private static final String SPEECH_LAST_FINAL_LANGUAGE_SOURCE_KEY = "last_final_language_source";
    private static final String DEFAULT_SPEECH_LANGUAGE = "ru-RU";

    private static Context appContext;
    private static Object factorySingleton;
    private static Field factoryInstanceField;
    private static Field factoryCurrentProfileIdField;

    private static Object originalInstance;
    private static Object originalProfileId;
    private static Object proxyInstance;

    private static String russianVoiceId = "";
    private static String russianLocaleTag = "ru-RU";

    private static boolean installed;
    private static String installError = "";

    private static long routedSpeakCount;
    private static long russianSpeakCount;
    private static long spanishSpeakCount;
    private static long passthroughSpeakCount;

    private static String lastConfiguredSpeechLanguage = "";
    private static String lastSpeechLanguageSource = "";
    private static String lastRoute = "";
    private static String lastTextPreview = "";
    private static String lastAppliedVoiceId = "";
    private static String lastAppliedLocaleTag = "";
    private static String lastRoutingError = "";

    private AndroidVoiceServiceInjector() {}

    public static boolean install(Context context) {
        synchronized (LOCK) {
            try {
                final Context app = context.getApplicationContext();
                appContext = app;

                Class<?> factoryClass =
                    Class.forName("com.ai.assistance.operit.api.voice.VoiceServiceFactory");

                Field singletonField = factoryClass.getDeclaredField("INSTANCE");
                singletonField.setAccessible(true);
                factorySingleton = singletonField.get(null);

                factoryInstanceField = findField(factoryClass, "instance");
                factoryCurrentProfileIdField = findField(factoryClass, "currentProfileId");

                Method getInstance = factoryClass.getDeclaredMethod("getInstance", Context.class);
                getInstance.setAccessible(true);

                Object current = getInstance.invoke(factorySingleton, app);

                if (installed && current == proxyInstance) {
                    installError = "";
                    return true;
                }

                // If another factory refresh replaced our proxy, treat the fresh service as the new delegate.
                originalInstance = current;
                originalProfileId = getField(factoryCurrentProfileIdField, factorySingleton);

                russianVoiceId = stringField(originalInstance, "currentVoiceId", "");
                russianLocaleTag = stringField(originalInstance, "currentLocaleTag", "ru-RU");
                if (russianLocaleTag == null || russianLocaleTag.trim().isEmpty()) {
                    russianLocaleTag = "ru-RU";
                }

                Class<?> voiceServiceInterface =
                    Class.forName("com.ai.assistance.operit.api.voice.VoiceService");

                final Object delegate = originalInstance;

                proxyInstance = Proxy.newProxyInstance(
                    voiceServiceInterface.getClassLoader(),
                    new Class<?>[] { voiceServiceInterface },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();

                            if ("toString".equals(name)) {
                                return "AndroidVoiceServiceAdapterProxy(" + delegate.getClass().getName() + ")";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && proxy == args[0];
                            }

                            if ("speak".equals(name) && args != null && args.length > 0) {
                                String text = args[0] == null ? "" : String.valueOf(args[0]);
                                routeBeforeSpeak(delegate, text);
                            }

                            try {
                                method.setAccessible(true);
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException ite) {
                                Throwable cause = ite.getCause();
                                if (cause != null) throw cause;
                                throw ite;
                            }
                        }
                    }
                );

                setField(factoryInstanceField, factorySingleton, proxyInstance);

                Object returned = getInstance.invoke(factorySingleton, app);
                installed = returned == proxyInstance;
                installError = installed ? "" : "VoiceServiceFactory did not retain proxy";
                return installed;
            } catch (Throwable t) {
                installError = describe(t);
                installed = false;
                return false;
            }
        }
    }

    private static void routeBeforeSpeak(Object delegate, String text) {
        synchronized (LOCK) {
            routedSpeakCount++;
            lastTextPreview = preview(text);
            lastRoutingError = "";

            try {
                String configured = getCommittedSpeechLanguage();
                lastConfiguredSpeechLanguage = configured;
                lastSpeechLanguageSource = getCommittedSpeechLanguageSource();

                String route = chooseRoute(configured);
                lastRoute = route;

                if ("ru-RU".equals(route)) {
                    if (russianVoiceId != null && !russianVoiceId.trim().isEmpty()) {
                        writeOptionalField(delegate, "currentVoiceId", russianVoiceId);
                        lastAppliedVoiceId = russianVoiceId;
                    } else {
                        writeOptionalField(delegate, "currentVoiceId", null);
                        lastAppliedVoiceId = "";
                    }
                    writeOptionalField(delegate, "currentLocaleTag", russianLocaleTag);
                    lastAppliedLocaleTag = russianLocaleTag;
                    russianSpeakCount++;
                    return;
                }

                if ("es-US".equals(route)) {
                    // Deliberately clear fixed voice selection. SimpleVoiceProvider will call
                    // setLanguage(es-US) and let Google TTS choose its normal local es-US voice.
                    writeOptionalField(delegate, "currentVoiceId", null);
                    writeOptionalField(delegate, "currentLocaleTag", "es-US");
                    lastAppliedVoiceId = "";
                    lastAppliedLocaleTag = "es-US";
                    spanishSpeakCount++;
                    return;
                }

                passthroughSpeakCount++;
            } catch (Throwable t) {
                passthroughSpeakCount++;
                lastRoutingError = describe(t);
            }
        }
    }

    private static String chooseRoute(String configured) {
        String normalized = configured == null ? "" : configured.trim().replace('_', '-');
        if (normalized.regionMatches(true, 0, "es-", 0, 3)
            || normalized.equalsIgnoreCase("es")) return "es-US";
        if (normalized.regionMatches(true, 0, "ru-", 0, 3)
            || normalized.equalsIgnoreCase("ru")) return "ru-RU";
        return "passthrough";
    }

    private static String getCommittedSpeechLanguage() {
        Context app = appContext;
        if (app == null) return DEFAULT_SPEECH_LANGUAGE;
        SharedPreferences prefs = app.getSharedPreferences(SPEECH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString(
            SPEECH_LAST_FINAL_LANGUAGE_KEY,
            prefs.getString(SPEECH_LANGUAGE_KEY, DEFAULT_SPEECH_LANGUAGE)
        );
    }

    private static String getCommittedSpeechLanguageSource() {
        Context app = appContext;
        if (app == null) return "DEFAULT";
        SharedPreferences prefs = app.getSharedPreferences(SPEECH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString(SPEECH_LAST_FINAL_LANGUAGE_SOURCE_KEY, "LEGACY_OR_CONFIG");
    }

    private static String getConfiguredSpeechLanguage() {
        Context app = appContext;
        if (app == null) return DEFAULT_SPEECH_LANGUAGE;

        SharedPreferences prefs =
            app.getSharedPreferences(SPEECH_PREFS, Context.MODE_PRIVATE);
        return prefs.getString(SPEECH_LANGUAGE_KEY, DEFAULT_SPEECH_LANGUAGE);
    }

    public static String getStatusJson(Context context) {
        synchronized (LOCK) {
            JSONObject out = new JSONObject();
            try {
                boolean installOk = install(context);

                Object current = factoryInstanceField == null
                    ? null
                    : getField(factoryInstanceField, factorySingleton);

                out.put("installed", installed);
                out.put("installCallOk", installOk);
                out.put("factoryPointsToProxy", current == proxyInstance);
                out.put("installError", installError);

                out.put(
                    "originalInstanceClass",
                    originalInstance == null
                        ? JSONObject.NULL
                        : originalInstance.getClass().getName()
                );
                out.put(
                    "originalProfileId",
                    originalProfileId == null
                        ? JSONObject.NULL
                        : String.valueOf(originalProfileId)
                );

                out.put("russianVoiceId", russianVoiceId);
                out.put("russianLocaleTag", russianLocaleTag);
                out.put("configuredSpeechLanguage", getConfiguredSpeechLanguage());
                out.put("committedSpeechLanguage", getCommittedSpeechLanguage());
                out.put("committedSpeechLanguageSource", getCommittedSpeechLanguageSource());

                out.put("routedSpeakCount", routedSpeakCount);
                out.put("russianSpeakCount", russianSpeakCount);
                out.put("spanishSpeakCount", spanishSpeakCount);
                out.put("passthroughSpeakCount", passthroughSpeakCount);

                out.put("lastConfiguredSpeechLanguage", lastConfiguredSpeechLanguage);
                out.put("lastSpeechLanguageSource", lastSpeechLanguageSource);
                out.put("lastRoute", lastRoute);
                out.put("lastTextPreview", lastTextPreview);
                out.put("lastAppliedVoiceId", lastAppliedVoiceId);
                out.put("lastAppliedLocaleTag", lastAppliedLocaleTag);
                out.put("lastRoutingError", lastRoutingError);

                if (originalInstance != null) {
                    out.put(
                        "delegateCurrentVoiceId",
                        jsonValue(readOptionalField(originalInstance, "currentVoiceId"))
                    );
                    out.put(
                        "delegateCurrentLocaleTag",
                        jsonValue(readOptionalField(originalInstance, "currentLocaleTag"))
                    );
                }

                out.put("ok", installed && current == proxyInstance);
            } catch (Throwable t) {
                try {
                    out.put("ok", false);
                    out.put("error", describe(t));
                } catch (Throwable ignored) {}
            }
            return out.toString();
        }
    }

    public static boolean uninstallRuntime() {
        synchronized (LOCK) {
            try {
                if (factorySingleton != null && factoryInstanceField != null) {
                    setField(factoryInstanceField, factorySingleton, originalInstance);
                }
                if (factorySingleton != null && factoryCurrentProfileIdField != null) {
                    setField(factoryCurrentProfileIdField, factorySingleton, originalProfileId);
                }
                installed = false;
                proxyInstance = null;
                return true;
            } catch (Throwable t) {
                installError = describe(t);
                return false;
            }
        }
    }

    private static Field findField(Class<?> type, String name) throws Exception {
        Class<?> c = type;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object getField(Field f, Object owner) throws Exception {
        return f.get(Modifier.isStatic(f.getModifiers()) ? null : owner);
    }

    private static void setField(Field f, Object owner, Object value) throws Exception {
        f.set(Modifier.isStatic(f.getModifiers()) ? null : owner, value);
    }

    private static Object readOptionalField(Object target, String name) {
        if (target == null) return null;
        try {
            Field f = findField(target.getClass(), name);
            return getField(f, target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeOptionalField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        setField(f, target, value);
    }

    private static String stringField(Object target, String name, String fallback) {
        Object value = readOptionalField(target, name);
        if (value == null) return fallback;
        return String.valueOf(value);
    }

    private static Object jsonValue(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof String
            || value instanceof Number
            || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    private static String preview(String text) {
        if (text == null) return "";
        String oneLine = text.replace("\n", "\\n");
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120);
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException
            && ((InvocationTargetException) c).getCause() != null) {
            c = ((InvocationTargetException) c).getCause();
        }
        return c.getClass().getName() + ": " + String.valueOf(c.getMessage());
    }
}
