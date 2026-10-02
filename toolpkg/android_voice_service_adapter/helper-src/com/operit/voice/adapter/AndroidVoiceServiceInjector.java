package com.operit.voice.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AndroidVoiceServiceInjector {
    private static final Object LOCK = new Object();

    private static final String SPEECH_PREFS = "operit_android_speech_service_adapter";
    private static final String SPEECH_LANGUAGE_KEY = "language";
    private static final String DEFAULT_SPEECH_LANGUAGE = "ru-RU";

    // Cross-ToolPkg in-process coordination. SharedPreferences.apply() updates the
    // in-memory map synchronously, while persistence happens asynchronously.
    private static final String DUPLEX_PREFS = "operit_voice_duplex_coordination";
    private static final String DUPLEX_KEY_SEQ = "tts_seq";
    private static final String DUPLEX_KEY_STATE = "tts_state";
    private static final String DUPLEX_KEY_REQUEST_AT = "tts_request_at_elapsed";
    private static final String DUPLEX_KEY_STATE_AT = "tts_state_at_elapsed";
    private static final String DUPLEX_KEY_PREVIEW = "tts_text_preview";

    private static final String DUPLEX_STATE_IDLE = "IDLE";
    private static final String DUPLEX_STATE_REQUESTED = "REQUESTED";
    private static final String DUPLEX_STATE_SPEAKING = "SPEAKING";
    private static final String DUPLEX_STATE_COMPLETED = "COMPLETED";
    private static final String DUPLEX_STATE_FAILED = "FAILED";
    private static final String DUPLEX_STATE_START_TIMEOUT = "START_TIMEOUT";

    private static final long DUPLEX_POLL_MS = 60L;
    private static final long DUPLEX_START_TIMEOUT_MS = 6000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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

    private static final int SPEAK_HISTORY_LIMIT = 8;
    private static final ArrayDeque<String> recentRawSpeakPreviews = new ArrayDeque<>();
    private static final ArrayDeque<String> recentSanitizedSpeakPreviews = new ArrayDeque<>();

    private static long routedSpeakCount;
    private static long russianSpeakCount;
    private static long spanishSpeakCount;
    private static long passthroughSpeakCount;
    private static long sanitizedSpeakCount;
    private static long removedMarkdownTokenCount;

    private static long duplexSequence;
    private static long duplexRequestCount;
    private static long duplexSpeakingObservedCount;
    private static long duplexCompletedCount;
    private static long duplexFailedCount;
    private static long duplexStartTimeoutCount;
    private static long lastDuplexRequestAtElapsed;
    private static long lastDuplexStateAtElapsed;
    private static String lastDuplexState = DUPLEX_STATE_IDLE;
    private static String lastDuplexTextPreview = "";

    private static String lastConfiguredSpeechLanguage = "";
    private static String lastRoute = "";
    private static String lastTextPreview = "";
    private static String lastRawTextPreview = "";
    private static String lastSanitizedTextPreview = "";
    private static int lastRemovedMarkdownTokenCount;
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

                            long duplexSeq = -1L;
                            if ("speak".equals(name) && args != null && args.length > 0) {
                                String rawText = args[0] == null ? "" : String.valueOf(args[0]);
                                String preparedText = prepareTextBeforeSpeak(delegate, rawText);
                                args[0] = preparedText;
                                duplexSeq = markDuplexRequested(preparedText);
                                scheduleDuplexMonitor(delegate, duplexSeq);
                            }

                            try {
                                method.setAccessible(true);
                                Object result = method.invoke(delegate, args);

                                if (duplexSeq > 0L && result instanceof Boolean
                                    && !((Boolean) result).booleanValue()) {
                                    markDuplexTerminal(
                                        duplexSeq,
                                        DUPLEX_STATE_FAILED,
                                        false
                                    );
                                }
                                return result;
                            } catch (InvocationTargetException ite) {
                                if (duplexSeq > 0L) {
                                    markDuplexTerminal(
                                        duplexSeq,
                                        DUPLEX_STATE_FAILED,
                                        false
                                    );
                                }
                                Throwable cause = ite.getCause();
                                if (cause != null) throw cause;
                                throw ite;
                            } catch (Throwable t) {
                                if (duplexSeq > 0L) {
                                    markDuplexTerminal(
                                        duplexSeq,
                                        DUPLEX_STATE_FAILED,
                                        false
                                    );
                                }
                                throw t;
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

    private static long markDuplexRequested(String text) {
        synchronized (LOCK) {
            long seq = ++duplexSequence;
            long now = SystemClock.elapsedRealtime();

            duplexRequestCount++;
            lastDuplexRequestAtElapsed = now;
            lastDuplexStateAtElapsed = now;
            lastDuplexState = DUPLEX_STATE_REQUESTED;
            lastDuplexTextPreview = preview(text);

            writeDuplexState(
                seq,
                DUPLEX_STATE_REQUESTED,
                now,
                now,
                lastDuplexTextPreview
            );
            return seq;
        }
    }

    private static void scheduleDuplexMonitor(
        final Object delegate,
        final long seq
    ) {
        final long startedAt = SystemClock.elapsedRealtime();

        MAIN.post(
            new Runnable() {
                private boolean observedSpeaking = false;

                @Override
                public void run() {
                    synchronized (LOCK) {
                        if (seq != duplexSequence) {
                            return;
                        }

                        long now = SystemClock.elapsedRealtime();
                        boolean speaking =
                            readBooleanNoArgs(
                                delegate,
                                false,
                                "isSpeaking",
                                "getIsSpeaking"
                            );

                        if (speaking) {
                            if (!observedSpeaking) {
                                observedSpeaking = true;
                                duplexSpeakingObservedCount++;
                                lastDuplexState = DUPLEX_STATE_SPEAKING;
                                lastDuplexStateAtElapsed = now;
                                writeDuplexState(
                                    seq,
                                    DUPLEX_STATE_SPEAKING,
                                    lastDuplexRequestAtElapsed,
                                    now,
                                    lastDuplexTextPreview
                                );
                            }

                            MAIN.postDelayed(this, DUPLEX_POLL_MS);
                            return;
                        }

                        if (observedSpeaking) {
                            markDuplexTerminal(
                                seq,
                                DUPLEX_STATE_COMPLETED,
                                true
                            );
                            return;
                        }

                        if (now - startedAt >= DUPLEX_START_TIMEOUT_MS) {
                            markDuplexTerminal(
                                seq,
                                DUPLEX_STATE_START_TIMEOUT,
                                false
                            );
                            return;
                        }

                        MAIN.postDelayed(this, DUPLEX_POLL_MS);
                    }
                }
            }
        );
    }

    private static void markDuplexTerminal(
        long seq,
        String state,
        boolean completedNormally
    ) {
        synchronized (LOCK) {
            if (seq != duplexSequence) return;

            long now = SystemClock.elapsedRealtime();
            lastDuplexState = state;
            lastDuplexStateAtElapsed = now;

            if (completedNormally) {
                duplexCompletedCount++;
            } else if (DUPLEX_STATE_START_TIMEOUT.equals(state)) {
                duplexStartTimeoutCount++;
            } else {
                duplexFailedCount++;
            }

            writeDuplexState(
                seq,
                state,
                lastDuplexRequestAtElapsed,
                now,
                lastDuplexTextPreview
            );
        }
    }

    private static void writeDuplexState(
        long seq,
        String state,
        long requestAt,
        long stateAt,
        String preview
    ) {
        Context app = appContext;
        if (app == null) return;

        app.getSharedPreferences(DUPLEX_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(DUPLEX_KEY_SEQ, seq)
            .putString(DUPLEX_KEY_STATE, state)
            .putLong(DUPLEX_KEY_REQUEST_AT, requestAt)
            .putLong(DUPLEX_KEY_STATE_AT, stateAt)
            .putString(DUPLEX_KEY_PREVIEW, preview == null ? "" : preview)
            .apply();
    }

    private static boolean readBooleanNoArgs(
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

    private static String prepareTextBeforeSpeak(Object delegate, String rawText) {
        synchronized (LOCK) {
            routedSpeakCount++;
            lastRawTextPreview = preview(rawText);

            SanitizeResult sanitizeResult = sanitizeForSpeech(rawText);
            String text = sanitizeResult.text;

            lastSanitizedTextPreview = preview(text);
            lastTextPreview = lastSanitizedTextPreview;
            lastRemovedMarkdownTokenCount = sanitizeResult.removedTokenCount;
            if (sanitizeResult.removedTokenCount > 0) {
                sanitizedSpeakCount++;
                removedMarkdownTokenCount += sanitizeResult.removedTokenCount;
            }

            appendHistory(recentRawSpeakPreviews, lastRawTextPreview);
            appendHistory(recentSanitizedSpeakPreviews, lastSanitizedTextPreview);

            lastRoutingError = "";

            try {
                String configured = getConfiguredSpeechLanguage();
                lastConfiguredSpeechLanguage = configured;

                String route = chooseRoute(configured, text);
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
                    return text;
                }

                if ("es-US".equals(route)) {
                    writeOptionalField(delegate, "currentVoiceId", null);
                    writeOptionalField(delegate, "currentLocaleTag", "es-US");
                    lastAppliedVoiceId = "";
                    lastAppliedLocaleTag = "es-US";
                    spanishSpeakCount++;
                    return text;
                }

                passthroughSpeakCount++;
            } catch (Throwable t) {
                passthroughSpeakCount++;
                lastRoutingError = describe(t);
            }

            return text;
        }
    }

    private static SanitizeResult sanitizeForSpeech(String text) {
        if (text == null || text.isEmpty()) {
            return new SanitizeResult("", 0);
        }

        String cleaned = text;
        int removed = 0;
        String tick = Character.toString((char) 96);
        String[] tokens = new String[] {
            "***", "___", "**", "__", "~~", tick + tick + tick, tick
        };

        for (String token : tokens) {
            int count = countOccurrences(cleaned, token);
            if (count > 0) {
                cleaned = cleaned.replace(token, "");
                removed += count;
            }
        }

        return new SanitizeResult(cleaned, removed);
    }

    private static int countOccurrences(String text, String token) {
        if (text == null || text.isEmpty() || token == null || token.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) break;
            count++;
            from = idx + token.length();
        }
        return count;
    }

    private static void appendHistory(ArrayDeque<String> history, String value) {
        history.addLast(value == null ? "" : value);
        while (history.size() > SPEAK_HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private static JSONArray historyJson(ArrayDeque<String> history) {
        JSONArray out = new JSONArray();
        for (String value : history) {
            out.put(value);
        }
        return out;
    }

    private static final class SanitizeResult {
        final String text;
        final int removedTokenCount;

        SanitizeResult(String text, int removedTokenCount) {
            this.text = text;
            this.removedTokenCount = removedTokenCount;
        }
    }

    private static String chooseRoute(String configured, String text) {
        // Protect Russian greetings/quoted Cyrillic even when the active STT session is Spanish.
        if (containsCyrillic(text)) {
            return "ru-RU";
        }

        String normalized = configured == null ? "" : configured.trim().replace('_', '-');
        if (normalized.regionMatches(true, 0, "es-", 0, 3)
            || normalized.equalsIgnoreCase("es")) {
            return "es-US";
        }
        if (normalized.regionMatches(true, 0, "ru-", 0, 3)
            || normalized.equalsIgnoreCase("ru")) {
            return "ru-RU";
        }

        return "passthrough";
    }

    private static boolean containsCyrillic(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.CYRILLIC
                || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
                || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
                || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
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

                out.put("routedSpeakCount", routedSpeakCount);
                out.put("russianSpeakCount", russianSpeakCount);
                out.put("spanishSpeakCount", spanishSpeakCount);
                out.put("passthroughSpeakCount", passthroughSpeakCount);
                out.put("sanitizedSpeakCount", sanitizedSpeakCount);
                out.put("removedMarkdownTokenCount", removedMarkdownTokenCount);

                out.put("duplexCoordinationEnabled", true);
                out.put("duplexSequence", duplexSequence);
                out.put("duplexRequestCount", duplexRequestCount);
                out.put("duplexSpeakingObservedCount", duplexSpeakingObservedCount);
                out.put("duplexCompletedCount", duplexCompletedCount);
                out.put("duplexFailedCount", duplexFailedCount);
                out.put("duplexStartTimeoutCount", duplexStartTimeoutCount);
                out.put("lastDuplexState", lastDuplexState);
                out.put("lastDuplexTextPreview", lastDuplexTextPreview);
                out.put(
                    "lastDuplexRequestAgeMs",
                    lastDuplexRequestAtElapsed <= 0L
                        ? -1L
                        : Math.max(
                            0L,
                            SystemClock.elapsedRealtime() - lastDuplexRequestAtElapsed
                        )
                );
                out.put(
                    "lastDuplexStateAgeMs",
                    lastDuplexStateAtElapsed <= 0L
                        ? -1L
                        : Math.max(
                            0L,
                            SystemClock.elapsedRealtime() - lastDuplexStateAtElapsed
                        )
                );

                out.put("lastConfiguredSpeechLanguage", lastConfiguredSpeechLanguage);
                out.put("lastRoute", lastRoute);
                out.put("lastTextPreview", lastTextPreview);
                out.put("lastRawTextPreview", lastRawTextPreview);
                out.put("lastSanitizedTextPreview", lastSanitizedTextPreview);
                out.put("lastRemovedMarkdownTokenCount", lastRemovedMarkdownTokenCount);
                out.put("recentRawSpeakPreviews", historyJson(recentRawSpeakPreviews));
                out.put("recentSanitizedSpeakPreviews", historyJson(recentSanitizedSpeakPreviews));
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
