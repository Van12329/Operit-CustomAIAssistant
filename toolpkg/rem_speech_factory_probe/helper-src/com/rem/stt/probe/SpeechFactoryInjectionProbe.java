package com.rem.stt.probe;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.json.JSONObject;

/**
 * N01: passive SpeechServiceFactory injection probe.
 *
 * It never starts recognition and never requests microphone access.
 * The proxy is installed only inside this call and the factory fields are restored in finally.
 */
public final class SpeechFactoryInjectionProbe {
    private static final String FACTORY =
        "com.ai.assistance.operit.api.speech.SpeechServiceFactory";
    private static final String SPEECH_SERVICE =
        "com.ai.assistance.operit.api.speech.SpeechService";
    private static final String SPEECH_MANAGER =
        "com.ai.assistance.operit.ui.floating.voice.SpeechInteractionManager";

    private SpeechFactoryInjectionProbe() {}

    public static String runProbe(Context context) {
        JSONObject out = new JSONObject();
        Context app = context.getApplicationContext();

        Field instanceField = null;
        Field profileField = null;
        Object originalInstance = null;
        String originalProfileId = null;
        Object warmedInstance = null;
        boolean warmedFactory = false;
        boolean restored = false;

        try {
            ClassLoader appLoader = app.getClassLoader();
            Class<?> factoryClass = Class.forName(FACTORY, false, appLoader);
            Class<?> speechServiceClass = Class.forName(SPEECH_SERVICE, false, appLoader);

            Field singletonField = factoryClass.getField("INSTANCE");
            Object factorySingleton = singletonField.get(null);

            instanceField = factoryClass.getDeclaredField("instance");
            profileField = factoryClass.getDeclaredField("currentProfileId");
            instanceField.setAccessible(true);
            profileField.setAccessible(true);

            originalInstance = instanceField.get(null);
            Object rawOriginalProfile = profileField.get(null);
            originalProfileId =
                rawOriginalProfile instanceof String ? (String) rawOriginalProfile : null;

            boolean originalFieldsConsistent =
                (originalInstance == null) == (originalProfileId == null);

            out.put("probe", "N01_SPEECH_FACTORY_INJECTION");
            out.put("microphoneTouched", false);
            out.put("recognitionStarted", false);
            out.put("factoryClass", factoryClass.getName());
            out.put("speechServiceInterface", speechServiceClass.getName());
            out.put("originalInstancePresent", originalInstance != null);
            out.put("originalProfilePresent", originalProfileId != null);
            out.put("originalFieldsConsistent", originalFieldsConsistent);
            out.put(
                "originalInstanceClass",
                originalInstance == null ? JSONObject.NULL : originalInstance.getClass().getName()
            );

            if (!originalFieldsConsistent) {
                out.put("ok", false);
                out.put(
                    "error",
                    "SpeechServiceFactory fields were inconsistent before probe; no injection attempted."
                );
                return out.toString();
            }

            Method factoryGetInstance =
                factoryClass.getMethod("getInstance", Context.class);

            String effectiveProfileId = originalProfileId;
            if (originalInstance == null) {
                // getInstance() only creates/returns the configured provider; it does not start recognition.
                warmedInstance = factoryGetInstance.invoke(factorySingleton, app);
                warmedFactory = true;

                Object rawProfile = profileField.get(null);
                effectiveProfileId =
                    rawProfile instanceof String ? (String) rawProfile : null;

                out.put(
                    "warmInstanceClass",
                    warmedInstance == null ? JSONObject.NULL : warmedInstance.getClass().getName()
                );
                out.put("warmProfilePresent", effectiveProfileId != null);

                if (warmedInstance == null || effectiveProfileId == null) {
                    out.put("ok", false);
                    out.put(
                        "error",
                        "Could not establish a stable factory baseline without starting recognition."
                    );
                    return out.toString();
                }
            }

            final Object unitInstance =
                Class.forName("kotlin.Unit", false, appLoader).getField("INSTANCE").get(null);

            InvocationHandler speechHandler = (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) return "RemN01SpeechServiceProxy";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) {
                    return args != null && args.length == 1 && proxy == args[0];
                }

                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) return false;
                if (returnType == byte.class) return (byte) 0;
                if (returnType == short.class) return (short) 0;
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                if (returnType == float.class) return 0f;
                if (returnType == double.class) return 0d;
                if (returnType == char.class) return (char) 0;
                if ("kotlin.Unit".equals(returnType.getName())) return unitInstance;
                return null;
            };

            Object injectedProxy = Proxy.newProxyInstance(
                speechServiceClass.getClassLoader(),
                new Class<?>[] { speechServiceClass },
                speechHandler
            );

            instanceField.set(null, injectedProxy);
            profileField.set(null, effectiveProfileId);

            Object factoryResult1 = factoryGetInstance.invoke(factorySingleton, app);
            Object factoryResult2 = factoryGetInstance.invoke(factorySingleton, app);

            boolean factoryReturnedProxy = factoryResult1 == injectedProxy;
            boolean factorySticky = factoryResult2 == injectedProxy;

            out.put("proxyClass", injectedProxy.getClass().getName());
            out.put("proxyImplementsSpeechService", speechServiceClass.isInstance(injectedProxy));
            out.put("factoryReturnedProxy", factoryReturnedProxy);
            out.put("factoryReturnedSameProxyTwice", factorySticky);

            // Stronger half of N01: construct a new SpeechInteractionManager without
            // initialize()/startListening(). Its constructor should capture factory.getInstance().
            boolean managerConstructed = false;
            boolean managerCapturedProxy = false;
            String managerError = null;

            try {
                Class<?> managerClass = Class.forName(SPEECH_MANAGER, false, appLoader);
                Class<?> scopeClass =
                    Class.forName("kotlinx.coroutines.CoroutineScope", false, appLoader);
                Class<?> function2Class =
                    Class.forName("kotlin.jvm.functions.Function2", false, appLoader);
                Class<?> function1Class =
                    Class.forName("kotlin.jvm.functions.Function1", false, appLoader);
                Class<?> emptyContextClass =
                    Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, appLoader);
                Object emptyContext = emptyContextClass.getField("INSTANCE").get(null);

                Object scopeProxy = Proxy.newProxyInstance(
                    scopeClass.getClassLoader(),
                    new Class<?>[] { scopeClass },
                    (proxy, method, args) -> {
                        if ("getCoroutineContext".equals(method.getName())) return emptyContext;
                        if ("toString".equals(method.getName())) return "RemN01CoroutineScope";
                        return null;
                    }
                );

                Object function2Proxy = Proxy.newProxyInstance(
                    function2Class.getClassLoader(),
                    new Class<?>[] { function2Class },
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) return unitInstance;
                        if ("toString".equals(method.getName())) return "RemN01Function2";
                        return null;
                    }
                );

                Object function1Proxy = Proxy.newProxyInstance(
                    function1Class.getClassLoader(),
                    new Class<?>[] { function1Class },
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) return unitInstance;
                        if ("toString".equals(method.getName())) return "RemN01Function1";
                        return null;
                    }
                );

                Constructor<?> target = null;
                for (Constructor<?> ctor : managerClass.getConstructors()) {
                    Class<?>[] pt = ctor.getParameterTypes();
                    if (pt.length == 4
                        && Context.class.isAssignableFrom(pt[0])
                        && pt[1].isAssignableFrom(scopeClass)
                        && pt[2].isAssignableFrom(function2Class)
                        && pt[3].isAssignableFrom(function1Class)) {
                        target = ctor;
                        break;
                    }
                }

                if (target == null) {
                    throw new NoSuchMethodException(
                        "Expected 4-argument SpeechInteractionManager constructor not found"
                    );
                }

                Object manager =
                    target.newInstance(app, scopeProxy, function2Proxy, function1Proxy);
                managerConstructed = true;

                Object captured;
                try {
                    Method getter = managerClass.getMethod("getSpeechService");
                    captured = getter.invoke(manager);
                } catch (NoSuchMethodException noGetter) {
                    Field speechField = managerClass.getDeclaredField("speechService");
                    speechField.setAccessible(true);
                    captured = speechField.get(manager);
                }

                managerCapturedProxy = captured == injectedProxy;
                out.put(
                    "managerCapturedClass",
                    captured == null ? JSONObject.NULL : captured.getClass().getName()
                );
            } catch (Throwable managerFailure) {
                managerError = describe(managerFailure);
            }

            out.put("managerConstructed", managerConstructed);
            out.put("managerCapturedProxy", managerCapturedProxy);
            if (managerError != null) out.put("managerError", managerError);

            boolean pass =
                speechServiceClass.isInstance(injectedProxy)
                && factoryReturnedProxy
                && factorySticky
                && managerConstructed
                && managerCapturedProxy;

            out.put("ok", pass);
            out.put(
                "conclusion",
                pass
                    ? "N01_PASS_FACTORY_AND_MANAGER_CAPTURE"
                    : "N01_PARTIAL_OR_FAIL"
            );
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("error", describe(t));
            } catch (Throwable ignored) {
            }
        } finally {
            try {
                if (instanceField != null) instanceField.set(null, originalInstance);
                if (profileField != null) profileField.set(null, originalProfileId);

                if (warmedFactory && warmedInstance != null && originalInstance == null) {
                    try {
                        ClassLoader appLoader = app.getClassLoader();
                        Class<?> speechServiceClass =
                            Class.forName(SPEECH_SERVICE, false, appLoader);
                        Method shutdown = speechServiceClass.getMethod("shutdown");
                        shutdown.invoke(warmedInstance);
                    } catch (Throwable ignored) {
                    }
                }

                restored = true;
            } catch (Throwable restoreFailure) {
                try {
                    out.put("restoreError", describe(restoreFailure));
                } catch (Throwable ignored) {
                }
            }

            try {
                out.put("factoryRestored", restored);
            } catch (Throwable ignored) {
            }
        }

        return out.toString();
    }

    private static String describe(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String name = cur.getClass().getName();
        String message = cur.getMessage();
        return message == null || message.trim().isEmpty()
            ? name
            : name + ": " + message;
    }
}
