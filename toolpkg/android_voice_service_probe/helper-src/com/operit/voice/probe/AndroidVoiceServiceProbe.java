package com.operit.voice.probe;

import android.content.Context;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AndroidVoiceServiceProbe {
    private AndroidVoiceServiceProbe() {}

    public static String run(Context context) {
        JSONObject out = new JSONObject();
        Object factorySingleton = null;
        Field instanceField = null;
        Field currentProfileIdField = null;
        Object originalInstance = null;
        Object originalProfileId = null;
        boolean restored = false;

        try {
            final Context app = context.getApplicationContext();
            out.put("probe", "ANDROID_VOICE_SERVICE_FACTORY");
            out.put("microphoneUsed", false);
            out.put("audioPlayed", false);

            Class<?> factoryClass =
                Class.forName("com.ai.assistance.operit.api.voice.VoiceServiceFactory");
            out.put("factoryClass", factoryClass.getName());

            Field instanceSingletonField = factoryClass.getDeclaredField("INSTANCE");
            instanceSingletonField.setAccessible(true);
            factorySingleton = instanceSingletonField.get(null);

            Method getInstance = factoryClass.getDeclaredMethod("getInstance", Context.class);
            getInstance.setAccessible(true);

            // Force factory to materialize its current instance/profile pair first.
            Object materialized = getInstance.invoke(factorySingleton, app);

            instanceField = findField(factoryClass, "instance");
            currentProfileIdField = findField(factoryClass, "currentProfileId");
            originalInstance = getField(instanceField, factorySingleton);
            originalProfileId = getField(currentProfileIdField, factorySingleton);

            out.put("originalInstancePresent", originalInstance != null);
            out.put(
                "originalInstanceClass",
                originalInstance == null ? JSONObject.NULL : originalInstance.getClass().getName()
            );
            out.put(
                "originalProfileId",
                originalProfileId == null ? JSONObject.NULL : String.valueOf(originalProfileId)
            );

            if (originalInstance != null) {
                out.put(
                    "originalCurrentVoiceId",
                    readOptionalField(originalInstance, "currentVoiceId")
                );
                out.put(
                    "originalCurrentLocaleTag",
                    readOptionalField(originalInstance, "currentLocaleTag")
                );
                out.put(
                    "originalIsInitialized",
                    readOptionalField(originalInstance, "_isInitialized")
                );
            }

            Class<?> voiceServiceInterface =
                Class.forName("com.ai.assistance.operit.api.voice.VoiceService");
            out.put("voiceServiceInterface", voiceServiceInterface.getName());

            Object proxy = Proxy.newProxyInstance(
                voiceServiceInterface.getClassLoader(),
                new Class<?>[] { voiceServiceInterface },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("toString".equals(name)) return "AndroidVoiceServiceProbeProxy";
                        if ("hashCode".equals(name)) return System.identityHashCode(p);
                        if ("equals".equals(name)) return args != null && args.length == 1 && p == args[0];

                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == float.class) return 0f;
                        if (rt == double.class) return 0d;

                        // Kotlin suspend methods compile to Object return type.
                        if ("initialize".equals(name)
                            || "speak".equals(name)
                            || "stop".equals(name)
                            || "pause".equals(name)
                            || "resume".equals(name)
                            || "setVoice".equals(name)) {
                            return Boolean.TRUE;
                        }
                        if ("getAvailableVoices".equals(name)) {
                            return Collections.emptyList();
                        }
                        return null;
                    }
                }
            );

            out.put("proxyClass", proxy.getClass().getName());
            out.put(
                "proxyImplementsVoiceService",
                voiceServiceInterface.isInstance(proxy)
            );

            setField(instanceField, factorySingleton, proxy);

            Object returned1 = getInstance.invoke(factorySingleton, app);
            Object returned2 = getInstance.invoke(factorySingleton, app);
            out.put("factoryReturnedProxy", returned1 == proxy);
            out.put("factoryReturnedSameProxyTwice", returned1 == proxy && returned2 == proxy);

            // Restore factory before probing TextToSpeech.
            setField(instanceField, factorySingleton, originalInstance);
            setField(currentProfileIdField, factorySingleton, originalProfileId);
            restored = true;
            out.put("factoryRestored", true);

            JSONObject tts = probeTts(app);
            out.put("androidTts", tts);

            boolean ok =
                voiceServiceInterface.isInstance(proxy)
                && returned1 == proxy
                && returned2 == proxy
                && restored
                && tts.optBoolean("initialized", false);

            out.put("ok", ok);
            out.put(
                "conclusion",
                ok
                    ? "PASS_FACTORY_INJECTION_AND_TTS_ENUMERATION"
                    : "PARTIAL_OR_FAILED"
            );
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
            } catch (Throwable ignored) {
            }
        } finally {
            if (!restored && factorySingleton != null && instanceField != null) {
                try {
                    setField(instanceField, factorySingleton, originalInstance);
                    if (currentProfileIdField != null) {
                        setField(currentProfileIdField, factorySingleton, originalProfileId);
                    }
                    out.put("factoryRestored", true);
                } catch (Throwable restoreError) {
                    try {
                        out.put("factoryRestoreError", describe(restoreError));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        return out.toString();
    }

    private static JSONObject probeTts(Context app) throws Exception {
        JSONObject ttsOut = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger statusRef = new AtomicInteger(Integer.MIN_VALUE);

        TextToSpeech tts =
            new TextToSpeech(
                app,
                new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(int status) {
                        statusRef.set(status);
                        latch.countDown();
                    }
                }
            );

        try {
            boolean callback = latch.await(7000L, TimeUnit.MILLISECONDS);
            ttsOut.put("initCallbackReceived", callback);
            ttsOut.put("initStatus", statusRef.get());

            boolean initialized = callback && statusRef.get() == TextToSpeech.SUCCESS;
            ttsOut.put("initialized", initialized);
            if (!initialized) {
                return ttsOut;
            }

            ttsOut.put("defaultEngine", tts.getDefaultEngine());
            Locale currentLanguage = tts.getLanguage();
            ttsOut.put(
                "currentLanguage",
                currentLanguage == null ? JSONObject.NULL : currentLanguage.toLanguageTag()
            );

            JSONArray targetSupport = new JSONArray();
            String[] targets = new String[] {"ru-RU", "es-US", "es-ES", "es-AR"};
            for (String tag : targets) {
                JSONObject item = new JSONObject();
                item.put("locale", tag);
                int result = tts.isLanguageAvailable(Locale.forLanguageTag(tag));
                item.put("availabilityCode", result);
                item.put("available", result >= TextToSpeech.LANG_AVAILABLE);
                targetSupport.put(item);
            }
            ttsOut.put("targetLocaleSupport", targetSupport);

            JSONArray ruVoices = new JSONArray();
            JSONArray esVoices = new JSONArray();
            Set<Voice> voices = tts.getVoices();
            int allVoiceCount = voices == null ? 0 : voices.size();
            ttsOut.put("allVoiceCount", allVoiceCount);

            if (voices != null) {
                List<Voice> sorted = new ArrayList<>(voices);
                Collections.sort(
                    sorted,
                    new Comparator<Voice>() {
                        @Override
                        public int compare(Voice a, Voice b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    }
                );

                for (Voice v : sorted) {
                    Locale locale = v.getLocale();
                    if (locale == null) continue;
                    String language = locale.getLanguage();
                    if (!"ru".equalsIgnoreCase(language)
                        && !"es".equalsIgnoreCase(language)) {
                        continue;
                    }

                    JSONObject item = new JSONObject();
                    item.put("name", v.getName());
                    item.put("locale", locale.toLanguageTag());
                    item.put("quality", v.getQuality());
                    item.put("latency", v.getLatency());
                    item.put("networkRequired", v.isNetworkConnectionRequired());
                    item.put("features", new JSONArray(v.getFeatures()));

                    if ("ru".equalsIgnoreCase(language)) {
                        ruVoices.put(item);
                    } else {
                        esVoices.put(item);
                    }
                }
            }

            ttsOut.put("ruVoices", ruVoices);
            ttsOut.put("esVoices", esVoices);
            ttsOut.put("ruVoiceCount", ruVoices.length());
            ttsOut.put("esVoiceCount", esVoices.length());

            if (Build.VERSION.SDK_INT >= 21 && tts.getVoice() != null) {
                Voice current = tts.getVoice();
                JSONObject cv = new JSONObject();
                cv.put("name", current.getName());
                cv.put("locale", current.getLocale().toLanguageTag());
                cv.put("quality", current.getQuality());
                cv.put("latency", current.getLatency());
                cv.put("networkRequired", current.isNetworkConnectionRequired());
                ttsOut.put("currentVoice", cv);
            }
        } finally {
            try {
                tts.shutdown();
            } catch (Throwable ignored) {
            }
        }

        return ttsOut;
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
        try {
            Field f = findField(target.getClass(), name);
            Object value = getField(f, target);
            if (value == null) return JSONObject.NULL;
            if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
                return value;
            }
            return value.getClass().getName() + ":" + String.valueOf(value);
        } catch (Throwable ignored) {
            return JSONObject.NULL;
        }
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
