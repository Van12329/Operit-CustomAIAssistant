package com.rem.stt.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AndroidSttBridgeHelper {
    // v0.8.4: known-good v0.8.2 STT timing + bridge-only command accumulator.
    private static final String PREFS = "rem_android_stt_bridge";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String ACTION_WINDOW_SHOWN =
        "com.ai.assistance.operit.action.FLOATING_CHAT_WINDOW_SHOWN";
    private static final String ACTION_FLOATING_STOPPED =
        "com.ai.assistance.operit.action.FLOATING_CHAT_SERVICE_STOPPED";

    private static final String FLOATING_SERVICE =
        "com.ai.assistance.operit.services.FloatingChatService";
    private static final String SPEECH_FACTORY =
        "com.ai.assistance.operit.api.speech.SpeechServiceFactory";
    private static final String VOICE_FACTORY =
        "com.ai.assistance.operit.api.voice.VoiceServiceFactory";
    private static final String PROMPT_TYPE =
        "com.ai.assistance.operit.data.model.PromptFunctionType";

    private static final int INITIAL_HANDOFF_DELAY_MS = 900;
    private static final int STOCK_RELEASE_SETTLE_MS = 260;
    private static final int RESTART_AFTER_NO_MATCH_MS = 320;
    private static final int RESTART_AFTER_RESULT_MIN_MS = 650;
    private static final int SPEAKING_POLL_MS = 180;
    private static final int SPEAKING_MAX_WAIT_MS = 30000;

    private static final String READY_NOTIFICATION_CHANNEL =
        "rem_android_stt_bridge_status";
    private static final int READY_NOTIFICATION_ID = 0x52A2;

    private static volatile boolean installed = false;
    private static volatile boolean enabled = true;
    private static volatile boolean activeWakeSession = false;
    private static volatile boolean waitingForAi = false;
    private static volatile boolean recognizerRunning = false;
    private static volatile boolean runtimeBusyObserved = false;
    private static volatile boolean avatarBallEnabled = true;
    private static volatile boolean compactModeRequested = true;
    private static volatile String pendingCommandText = "";
    private static volatile long pendingCommandUntilElapsedMs = 0L;
    private static final int COMMAND_ACCUMULATOR_WINDOW_MS = 12000;

    private static SpeechRecognizer recognizer;
    private static long sessionGeneration = 0L;
    private static BroadcastReceiver receiver;

    // W09 opt1: keep the proven v0.8.6 recognition timings, but remove
    // avoidable main-thread I/O and repeated reflection/runBlocking work.
    private static volatile String lastPolledRuntimeState = "";

    private static Object cachedVoiceService;
    private static Method cachedVoiceIsSpeakingMethod;
    private static long cachedVoiceGeneration = -1L;

    private static Object cachedFloatingCompanion;
    private static Method cachedFloatingGetInstanceMethod;
    private static Method cachedFloatingGetInputStateMethod;
    private static Method cachedStateGetValueMethod;

    private static Object cachedSpeechFactorySingleton;
    private static Method cachedSpeechFactoryResetMethod;

    // Minimal observer-effect instrumentation: only end-to-end timestamps and
    // counters in RAM. No timing of every poll/commit/reflection call.
    private static volatile long perfWindowShownAt = 0L;
    private static volatile long perfActivateAt = 0L;
    private static volatile long perfStockReleaseStartAt = 0L;
    private static volatile long perfStockReleaseEndAt = 0L;
    private static volatile long perfFirstRecognizerStartAt = 0L;
    private static volatile long perfFirstReadyAt = 0L;
    private static volatile long perfSpeechEndAt = 0L;
    private static volatile long perfResultAt = 0L;
    private static volatile long perfSendAt = 0L;
    private static volatile long perfFirstAiBusyAt = 0L;
    private static volatile long perfFirstTtsSpeakingAt = 0L;
    private static volatile long perfTtsEndAt = 0L;
    private static volatile long perfTurnCompleteAt = 0L;
    private static volatile long perfNextReadyAt = 0L;
    private static volatile long perfRecognizerCreateCount = 0L;
    private static volatile boolean perfSawTtsSpeaking = false;

    private static final int PERF_HISTORY_LIMIT = 12;
    private static final ArrayList<PerfSample> perfHistory = new ArrayList<>();

    private static final class PerfSample {
        final long windowShownToFirstReadyMs;
        final long windowShownToActivateMs;
        final long activateToStockReleaseStartMs;
        final long stockReleaseCallMs;
        final long stockReleaseEndToRecognizerStartMs;
        final long recognizerStartToFirstReadyMs;
        final long speechEndToResultMs;
        final long resultToSendMs;
        final long sendToFirstAiBusyMs;
        final long sendToFirstTtsSpeakingMs;
        final long sendToTurnCompleteMs;
        final long ttsEndToNextReadyMs;
        final long turnCompleteToNextReadyMs;
        final long recognizerCreateCount;

        PerfSample() {
            windowShownToFirstReadyMs = deltaMs(perfWindowShownAt, perfFirstReadyAt);
            windowShownToActivateMs = deltaMs(perfWindowShownAt, perfActivateAt);
            activateToStockReleaseStartMs = deltaMs(perfActivateAt, perfStockReleaseStartAt);
            stockReleaseCallMs = deltaMs(perfStockReleaseStartAt, perfStockReleaseEndAt);
            stockReleaseEndToRecognizerStartMs =
                deltaMs(perfStockReleaseEndAt, perfFirstRecognizerStartAt);
            recognizerStartToFirstReadyMs =
                deltaMs(perfFirstRecognizerStartAt, perfFirstReadyAt);
            speechEndToResultMs = deltaMs(perfSpeechEndAt, perfResultAt);
            resultToSendMs = deltaMs(perfResultAt, perfSendAt);
            sendToFirstAiBusyMs = deltaMs(perfSendAt, perfFirstAiBusyAt);
            sendToFirstTtsSpeakingMs = deltaMs(perfSendAt, perfFirstTtsSpeakingAt);
            sendToTurnCompleteMs = deltaMs(perfSendAt, perfTurnCompleteAt);
            ttsEndToNextReadyMs = deltaMs(perfTtsEndAt, perfNextReadyAt);
            turnCompleteToNextReadyMs = deltaMs(perfTurnCompleteAt, perfNextReadyAt);
            recognizerCreateCount = perfRecognizerCreateCount;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("windowShownToFirstReadyMs", windowShownToFirstReadyMs);
            o.put("windowShownToActivateMs", windowShownToActivateMs);
            o.put("activateToStockReleaseStartMs", activateToStockReleaseStartMs);
            o.put("stockReleaseCallMs", stockReleaseCallMs);
            o.put("stockReleaseEndToRecognizerStartMs", stockReleaseEndToRecognizerStartMs);
            o.put("recognizerStartToFirstReadyMs", recognizerStartToFirstReadyMs);
            o.put("speechEndToResultMs", speechEndToResultMs);
            o.put("resultToSendMs", resultToSendMs);
            o.put("sendToFirstAiBusyMs", sendToFirstAiBusyMs);
            o.put("sendToFirstTtsSpeakingMs", sendToFirstTtsSpeakingMs);
            o.put("sendToTurnCompleteMs", sendToTurnCompleteMs);
            o.put("ttsEndToNextReadyMs", ttsEndToNextReadyMs);
            o.put("turnCompleteToNextReadyMs", turnCompleteToNextReadyMs);
            o.put("recognizerCreateCount", recognizerCreateCount);
            return o;
        }
    }

    private AndroidSttBridgeHelper() {}

    public static boolean install(Context context) {
        if (context == null) return false;
        final Context app = context.getApplicationContext();

        SharedPreferences bridgePrefs =
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        enabled = bridgePrefs.getBoolean("enabled", true);
        avatarBallEnabled = bridgePrefs.getBoolean("avatarBallEnabled", true);

        if (installed) {
            MAIN.post(() -> probeExistingWakeSession(app));
            return true;
        }

        synchronized (AndroidSttBridgeHelper.class) {
            if (installed) return true;

            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();

                    if (ACTION_WINDOW_SHOWN.equals(action)) {
                        MAIN.post(() -> onFloatingWindowShown(app));
                    } else if (ACTION_FLOATING_STOPPED.equals(action)) {
                        MAIN.post(() -> deactivate(app, "FLOATING_STOPPED"));
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_WINDOW_SHOWN);
            filter.addAction(ACTION_FLOATING_STOPPED);

            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(receiver, filter);
                }
                installed = true;
                if (enabled) {
                    mark(app, "readyElapsedMs", SystemClock.elapsedRealtime());
                    mark(app, "readyAtEpochMs", System.currentTimeMillis());
                    writeState(app, "READY", "", "", 0, "", "BRIDGE_READY");
                    showReadyNotification(app);
                } else {
                    writeState(app, "DISABLED", "", "", 0, "", "BRIDGE_LOADED_DISABLED");
                }
            } catch (Throwable t) {
                writeState(app, "INSTALL_ERROR", "", "", -401, describe(t), "INSTALL_ERROR");
                return false;
            }
        }

        MAIN.post(() -> probeExistingWakeSession(app));
        return true;
    }

    public static boolean setEnabled(Context context, boolean value) {
        if (context == null) return false;
        final Context app = context.getApplicationContext();

        enabled = value;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", value)
            .apply();

        MAIN.post(() -> {
            if (!value) {
                deactivate(app, "DISABLED");
            } else {
                probeExistingWakeSession(app);
            }
        });
        return true;
    }

    public static boolean setAvatarBallEnabled(Context context, boolean value) {
        if (context == null) return false;
        final Context app = context.getApplicationContext();

        avatarBallEnabled = value;
        compactModeRequested = value;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("avatarBallEnabled", value)
            .apply();

        MAIN.post(() -> {
            if (!value) {
                exitAvatarBall(app);
            } else if (activeWakeSession && recognizerRunning) {
                enterAvatarBall(app);
            }
        });
        return true;
    }

    public static void onFloatingRuntimeState(
        Context context,
        String state,
        boolean isActive
    ) {
        if (context == null || state == null) return;
        final Context app = context.getApplicationContext();
        final String normalized = state.trim().toLowerCase(Locale.ROOT);

        markString(app, "lastRuntimeState", normalized);
        mark(app, "lastRuntimeStateAtElapsedMs", SystemClock.elapsedRealtime());

        if (!enabled || !activeWakeSession) return;

        switch (normalized) {
            case "processing":
            case "connecting":
            case "receiving":
            case "executing_tool":
            case "tool_progress":
            case "processing_tool_result":
            case "summarizing":
            case "executing_plan":
                runtimeBusyObserved = true;
                waitingForAi = true;
                AvatarBallController.setState(app, "THINKING");
                cancelAndroidRecognizer(app, false, "RUNTIME_BUSY");
                break;

            case "completed":
            case "error":
                if (waitingForAi || runtimeBusyObserved) {
                    waitingForAi = false;
                    scheduleRestartAfterAi(app, RESTART_AFTER_RESULT_MIN_MS);
                }
                break;

            case "idle":
                if (!isActive && (waitingForAi || runtimeBusyObserved)) {
                    waitingForAi = false;
                    scheduleRestartAfterAi(app, RESTART_AFTER_RESULT_MIN_MS);
                }
                break;

            default:
                break;
        }
    }

    public static String getStatusJson(Context context) {
        if (context == null) return "{\"state\":\"NO_CONTEXT\"}";
        SharedPreferences p = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        try {
            JSONObject o = new JSONObject();
            o.put("probe", "W07-VOICE-SESSION");
            o.put("installed", installed);
            o.put("enabled", enabled);
            o.put("activeWakeSession", activeWakeSession);
            o.put("waitingForAi", waitingForAi);
            o.put("recognizerRunning", recognizerRunning);
            o.put("state", p.getString("state", "IDLE"));
            o.put("lastEvent", p.getString("lastEvent", ""));
            o.put("lastText", p.getString("lastText", ""));
            o.put("partial", p.getString("partial", ""));
            o.put("errorCode", p.getInt("errorCode", 0));
            o.put("error", p.getString("error", ""));
            o.put("lastRuntimeState", p.getString("lastRuntimeState", ""));
            o.put("sessionStarts", p.getInt("sessionStarts", 0));
            o.put("recognitionStarts", p.getInt("recognitionStarts", 0));
            o.put("resultsSent", p.getInt("resultsSent", 0));
            o.put("noMatchRetries", p.getInt("noMatchRetries", 0));
            o.put("sendFailures", p.getInt("sendFailures", 0));
            o.put("localCommands", p.getInt("localCommands", 0));
            o.put("lastLocalCommand", p.getString("lastLocalCommand", ""));
            o.put("language", "ru-RU");
            o.put("preferOffline", true);
            o.put("systemUi", false);
            o.put("avatarBallEnabled", avatarBallEnabled);
            o.put("compactModeRequested", compactModeRequested);
            o.put("avatarBallShown", AvatarBallController.isShown());
            o.put("avatarBallAsset", AvatarBallController.getCurrentAsset());
            o.put("pendingCommandText", pendingCommandText);
            o.put("pendingCommandUntilElapsedMs", pendingCommandUntilElapsedMs);
            o.put("readyElapsedMs", p.getLong("readyElapsedMs", 0L));
            o.put("readyAtEpochMs", p.getLong("readyAtEpochMs", 0L));
            o.put("readyNotificationPosted", p.getBoolean("readyNotificationPosted", false));
            o.put("updatedAtEpochMs", p.getLong("updatedAtEpochMs", 0L));

            o.put("perfMode", "OPT1_END_TO_END_RAM");
            o.put("fixedInitialDelayBudgetMs", INITIAL_HANDOFF_DELAY_MS + STOCK_RELEASE_SETTLE_MS);
            o.put("perf", buildCurrentPerfJson());
            JSONArray history = new JSONArray();
            synchronized (perfHistory) {
                for (PerfSample sample : perfHistory) {
                    history.put(sample.toJson());
                }
            }
            o.put("perfHistory", history);
            return o.toString();
        } catch (Throwable t) {
            return "{\"state\":\"STATUS_ERROR\",\"error\":\"" + escape(describe(t)) + "\"}";
        }
    }

    public static boolean forceProbe(Context context) {
        if (context == null) return false;
        MAIN.post(() -> probeExistingWakeSession(context.getApplicationContext()));
        return true;
    }

    private static void onFloatingWindowShown(Context app) {
        if (!enabled) return;

        boolean wakeLaunched = isWakeLaunched();
        setFlag(app, "lastWindowWakeLaunched", wakeLaunched);

        if (wakeLaunched) {
            archivePerfSampleIfUseful();
            resetPerfSession();
            perfWindowShownAt = SystemClock.elapsedRealtime();
        }

        if (!wakeLaunched) {
            writeState(app, "IGNORED", "", "", 0, "", "WINDOW_NOT_WAKE_LAUNCHED");
            return;
        }

        activate(app, "WINDOW_SHOWN_WAKE");
    }

    private static void probeExistingWakeSession(Context app) {
        if (!enabled) return;
        if (isWakeLaunched()) {
            activate(app, "PROBE_EXISTING_WAKE");
        }
    }

    private static void activate(Context app, String reason) {
        sessionGeneration++;
        final long generation = sessionGeneration;

        activeWakeSession = true;
        waitingForAi = false;
        runtimeBusyObserved = false;
        compactModeRequested = avatarBallEnabled;
        clearPendingCommand();
        invalidateVoiceCache();
        lastPolledRuntimeState = "";

        perfActivateAt = SystemClock.elapsedRealtime();
        increment(app, "sessionStarts");
        mark(app, "sessionActivatedElapsedMs", perfActivateAt);
        writeState(app, "ACTIVATING", "", "", 0, "", reason);

        // Let Operit's normal wake->fullscreen handoff finish first.
        // Then shut down only its STT provider and start Android SpeechRecognizer.
        MAIN.postDelayed(() -> {
            if (!isGenerationActive(generation)) return;

            perfStockReleaseStartAt = SystemClock.elapsedRealtime();
            shutdownOperitSpeechProvider(app);
            perfStockReleaseEndAt = SystemClock.elapsedRealtime();
            writeState(app, "STOCK_STT_RELEASE", "", "", 0, "", "STOCK_STT_SHUTDOWN");

            MAIN.postDelayed(() -> {
                if (!isGenerationActive(generation)) return;
                startWhenTtsIdle(app, generation, 0);
            }, STOCK_RELEASE_SETTLE_MS);
        }, INITIAL_HANDOFF_DELAY_MS);
    }

    private static void deactivate(Context app, String reason) {
        sessionGeneration++;
        activeWakeSession = false;
        waitingForAi = false;
        runtimeBusyObserved = false;
        clearPendingCommand();
        invalidateVoiceCache();
        lastPolledRuntimeState = "";

        cancelAndroidRecognizer(app, true, reason);
        AvatarBallController.hide(app);
        setOperitFloatingVisible(true);
        compactModeRequested = avatarBallEnabled;
        writeState(app, "INACTIVE", "", "", 0, "", reason);
    }

    private static boolean isGenerationActive(long generation) {
        return enabled && activeWakeSession && generation == sessionGeneration;
    }

    private static void scheduleRestartAfterAi(Context app, int delayMs) {
        final long generation = sessionGeneration;
        MAIN.postDelayed(() -> {
            if (!isGenerationActive(generation)) return;
            startWhenTtsIdle(app, generation, 0);
        }, Math.max(250, delayMs));
    }

    private static void startWhenTtsIdle(Context app, long generation, int waitedMs) {
        if (!isGenerationActive(generation) || waitingForAi) return;

        // The built-in wave UI may have started or retried its own provider.
        // Re-shutdown it immediately before each Android STT turn.
        shutdownOperitSpeechProvider(app);

        boolean speaking = isOperitVoiceSpeaking(app);
        if (speaking && waitedMs < SPEAKING_MAX_WAIT_MS) {
            if (waitedMs == 0) {
                writeState(app, "WAIT_TTS", "", "", 0, "", "WAIT_TTS");
            }
            MAIN.postDelayed(
                () -> startWhenTtsIdle(app, generation, waitedMs + SPEAKING_POLL_MS),
                SPEAKING_POLL_MS
            );
            return;
        }

        startAndroidRecognizer(app, generation);
    }

    private static void startAndroidRecognizer(Context app, long generation) {
        if (!isGenerationActive(generation) || waitingForAi || recognizerRunning) return;

        try {
            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                writeState(
                    app,
                    "ERROR",
                    "",
                    "",
                    -402,
                    "SpeechRecognizer is not available",
                    "ANDROID_STT_UNAVAILABLE"
                );
                return;
            }

            destroyRecognizerSilently();
            recognizer = SpeechRecognizer.createSpeechRecognizer(app);
            recognizerRunning = true;

            final AtomicBoolean finished = new AtomicBoolean(false);

            recognizer.setRecognitionListener(new RecognitionListener() {
                private String lastPartial = "";

                @Override
                public void onReadyForSpeech(Bundle params) {
                    if (!finished.get() && isGenerationActive(generation)) {
                        long now = SystemClock.elapsedRealtime();
                        if (perfFirstReadyAt == 0L) {
                            perfFirstReadyAt = now;
                        } else if (perfTurnCompleteAt > 0L && perfNextReadyAt == 0L) {
                            perfNextReadyAt = now;
                        }
                        writeState(app, "READY", "", lastPartial, 0, "", "READY");
                        AvatarBallController.setState(app, "LISTENING");
                        if (avatarBallEnabled &&
                            compactModeRequested &&
                            !AvatarBallController.isShown()) {
                            enterAvatarBall(app);
                        }
                    }
                }

                @Override
                public void onBeginningOfSpeech() {
                    if (!finished.get() && isGenerationActive(generation)) {
                        writeState(app, "LISTENING", "", lastPartial, 0, "", "BEGINNING_OF_SPEECH");
                        AvatarBallController.setState(app, "LISTENING");
                    }
                }

                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    if (!finished.get() && isGenerationActive(generation)) {
                        perfSpeechEndAt = SystemClock.elapsedRealtime();
                        writeState(app, "PROCESSING_STT", "", lastPartial, 0, "", "END_OF_SPEECH");
                        AvatarBallController.setState(app, "THINKING");
                    }
                }

                @Override
                public void onError(int error) {
                    if (!finished.compareAndSet(false, true)) return;

                    recognizerRunning = false;
                    destroyRecognizerSilently();

                    if (!isGenerationActive(generation)) return;

                    String name = errorName(error);
                    writeState(app, "STT_ERROR", "", lastPartial, error, name, "ERROR");

                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        increment(app, "noMatchRetries");
                        MAIN.postDelayed(
                            () -> startWhenTtsIdle(app, generation, 0),
                            RESTART_AFTER_NO_MATCH_MS
                        );
                    } else if (activeWakeSession) {
                        MAIN.postDelayed(
                            () -> startWhenTtsIdle(app, generation, 0),
                            900
                        );
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    String text = firstResult(results);
                    if (!finished.compareAndSet(false, true)) return;

                    recognizerRunning = false;
                    destroyRecognizerSilently();

                    if (!isGenerationActive(generation)) return;

                    if (text.isEmpty()) {
                        writeState(
                            app, "STT_ERROR", "", lastPartial, -403,
                            "Recognition returned no text", "EMPTY_RESULT"
                        );
                        MAIN.postDelayed(
                            () -> startWhenTtsIdle(app, generation, 0),
                            RESTART_AFTER_NO_MATCH_MS
                        );
                        return;
                    }

                    perfResultAt = SystemClock.elapsedRealtime();
                    markString(app, "lastText", text);
                    writeState(app, "RESULT", text, lastPartial, 0, "", "RESULT");

                    String routedText = text;
                    LocalCommand localCommand = classifyLocalCommand(routedText);
                    if (localCommand != LocalCommand.NONE) {
                        clearPendingCommand();
                        handleLocalCommand(app, generation, routedText, localCommand);
                        return;
                    }

                    if (hasPendingCommand()) {
                        String combined = combinePendingWith(routedText);
                        LocalCommand combinedCommand = classifyLocalCommand(combined);
                        if (combinedCommand != LocalCommand.NONE) {
                            clearPendingCommand();
                            handleLocalCommand(app, generation, combined, combinedCommand);
                            return;
                        }

                        if (isCommandPrefixCandidate(combined)) {
                            writeState(
                                app,
                                "COMMAND_WAIT",
                                combined,
                                "",
                                0,
                                "",
                                "LOCAL_COMMAND_ACCUMULATING"
                            );
                            MAIN.postDelayed(
                                () -> startWhenTtsIdle(app, generation, 0),
                                180
                            );
                            return;
                        }

                        // It was not a local command after all. Preserve the user's
                        // whole phrase and send the combined text to the agent.
                        routedText = combined;
                        clearPendingCommand();
                    } else if (isCommandPrefixCandidate(routedText)) {
                        setPendingCommand(routedText);
                        writeState(
                            app,
                            "COMMAND_WAIT",
                            routedText,
                            "",
                            0,
                            "",
                            "LOCAL_COMMAND_FRAGMENT"
                        );
                        MAIN.postDelayed(
                            () -> startWhenTtsIdle(app, generation, 0),
                            180
                        );
                        return;
                    }

                    waitingForAi = true;
                    runtimeBusyObserved = false;
                    AvatarBallController.setState(app, "THINKING");

                    perfSendAt = SystemClock.elapsedRealtime();
                    boolean sent = sendVoiceMessageToFloating(routedText);
                    if (sent) {
                        increment(app, "resultsSent");
                        writeState(app, "SENT_TO_OPERIT", routedText, lastPartial, 0, "", "VOICE_MESSAGE_SENT");

                        // The bridge is self-contained: poll FloatingChatService processing
                        // state and VoiceService speaking state, then resume Android STT.
                        MAIN.postDelayed(
                            () -> waitForAiAndTtsThenRestart(app, generation, 0, false),
                            220
                        );
                    } else {
                        increment(app, "sendFailures");
                        waitingForAi = false;
                        writeState(
                            app, "SEND_ERROR", text, lastPartial, -404,
                            "Could not deliver recognized text to FloatingChatService",
                            "VOICE_MESSAGE_SEND_FAILED"
                        );
                        MAIN.postDelayed(
                            () -> startWhenTtsIdle(app, generation, 0),
                            900
                        );
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    String text = firstResult(partialResults);
                    if (!text.isEmpty() && !finished.get() && isGenerationActive(generation)) {
                        lastPartial = text;
                        // One asynchronous state update is enough; avoid a second
                        // SharedPreferences edit for every partial callback.
                        writeState(app, "PARTIAL", "", lastPartial, 0, "", "PARTIAL");
                    }
                }

                @Override public void onEvent(int eventType, Bundle params) {}
            });

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

            increment(app, "recognitionStarts");
            long recognitionStartAt = SystemClock.elapsedRealtime();
            if (perfFirstRecognizerStartAt == 0L) {
                perfFirstRecognizerStartAt = recognitionStartAt;
            }
            perfRecognizerCreateCount++;
            mark(app, "lastRecognitionStartElapsedMs", recognitionStartAt);
            writeState(app, "STARTING", "", "", 0, "", "START_LISTENING");
            recognizer.startListening(intent);
        } catch (Throwable t) {
            recognizerRunning = false;
            destroyRecognizerSilently();
            writeState(app, "ERROR", "", "", -405, describe(t), "START_EXCEPTION");

            if (isGenerationActive(generation)) {
                MAIN.postDelayed(
                    () -> startWhenTtsIdle(app, generation, 0),
                    1200
                );
            }
        }
    }

    private static void cancelAndroidRecognizer(Context app, boolean destroy, String reason) {
        SpeechRecognizer r = recognizer;
        recognizerRunning = false;
        recognizer = null;

        if (r != null) {
            try { r.cancel(); } catch (Throwable ignored) {}
            if (destroy) {
                try { r.destroy(); } catch (Throwable ignored) {}
            } else {
                try { r.destroy(); } catch (Throwable ignored) {}
            }
        }

        markString(app, "lastCancelReason", reason == null ? "" : reason);
    }

    private static void destroyRecognizerSilently() {
        SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try { r.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static void waitForAiAndTtsThenRestart(
        Context app,
        long generation,
        int waitedMs,
        boolean observedActivity
    ) {
        if (!isGenerationActive(generation)) return;

        boolean aiBusy = isFloatingAiBusy();
        boolean speaking = isOperitVoiceSpeaking(app);
        boolean observed = observedActivity || aiBusy || speaking;
        long perfNow = SystemClock.elapsedRealtime();

        if (aiBusy && perfFirstAiBusyAt == 0L) {
            perfFirstAiBusyAt = perfNow;
        }
        if (speaking) {
            perfSawTtsSpeaking = true;
            if (perfFirstTtsSpeakingAt == 0L) {
                perfFirstTtsSpeakingAt = perfNow;
            }
        } else if (perfSawTtsSpeaking && perfTtsEndAt == 0L) {
            perfTtsEndAt = perfNow;
        }

        if (speaking) {
            AvatarBallController.setState(app, "SPEAKING");
        } else if (aiBusy) {
            AvatarBallController.setState(app, "THINKING");
        }

        markRuntimeStateIfChanged(
            app,
            aiBusy ? "processing" : (speaking ? "speaking" : "idle")
        );

        if (observed && !aiBusy && !speaking) {
            waitingForAi = false;
            runtimeBusyObserved = true;
            perfTurnCompleteAt = SystemClock.elapsedRealtime();
            writeState(app, "TURN_COMPLETE", "", "", 0, "", "AI_TTS_COMPLETE");
            AvatarBallController.setState(app, "IDLE");

            // Small grace period prevents a race where streaming TTS starts
            // just after the chat processing state becomes idle.
            MAIN.postDelayed(() -> {
                if (!isGenerationActive(generation)) return;
                startWhenTtsIdle(app, generation, 0);
            }, 900);
            return;
        }

        // If no busy/speaking state was observable (provider/UI timing edge case),
        // do not wedge the voice session forever.
        if (!observed && waitedMs >= 12000) {
            waitingForAi = false;
            writeState(app, "TURN_FALLBACK", "", "", 0, "", "AI_STATE_NOT_OBSERVED");
            scheduleRestartAfterAi(app, 900);
            return;
        }

        if (waitedMs >= 90000) {
            waitingForAi = false;
            writeState(app, "TURN_TIMEOUT", "", "", -406, "AI/TTS wait timeout", "AI_TTS_TIMEOUT");
            scheduleRestartAfterAi(app, 1200);
            return;
        }

        MAIN.postDelayed(
            () -> waitForAiAndTtsThenRestart(
                app,
                generation,
                waitedMs + 220,
                observed
            ),
            220
        );
    }

    private static boolean isFloatingAiBusy() {
        try {
            Object service = getFloatingServiceInstance();
            if (service == null) return false;

            Method getState = cachedFloatingGetInputStateMethod;
            if (getState == null || !getState.getDeclaringClass().isInstance(service)) {
                getState = service.getClass().getMethod("getInputProcessingState");
                cachedFloatingGetInputStateMethod = getState;
            }
            Object stateHolder = getState.invoke(service);
            if (stateHolder == null) return false;

            Method getValue = cachedStateGetValueMethod;
            if (getValue == null || !getValue.getDeclaringClass().isInstance(stateHolder)) {
                getValue = stateHolder.getClass().getMethod("getValue");
                cachedStateGetValueMethod = getValue;
            }
            Object value = getValue.invoke(stateHolder);
            if (value == null) return false;

            String simple = value.getClass().getSimpleName();
            if (simple == null) simple = "";
            String normalized = simple.toLowerCase(Locale.ROOT);

            return !normalized.contains("idle")
                && !normalized.contains("completed")
                && !normalized.contains("error");
        } catch (Throwable ignored) {
            cachedFloatingGetInputStateMethod = null;
            cachedStateGetValueMethod = null;
            return false;
        }
    }

    private static void showReadyNotification(Context app) {
        boolean posted = false;
        try {
            NotificationManager nm =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
                    setFlag(app, "readyNotificationPosted", false);
                    try {
                        Toast.makeText(
                            app,
                            "Android STT Bridge READY — можно говорить WakeWord",
                            Toast.LENGTH_LONG
                        ).show();
                    } catch (Throwable ignored) {}
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = nm.getNotificationChannel(READY_NOTIFICATION_CHANNEL);
                    if (channel == null) {
                        channel = new NotificationChannel(
                            READY_NOTIFICATION_CHANNEL,
                            "Rem Android STT Bridge",
                            NotificationManager.IMPORTANCE_DEFAULT
                        );
                        channel.setDescription("Status notifications for the Russian Android STT bridge");
                        nm.createNotificationChannel(channel);
                    }
                }

                String readyTime = String.valueOf(
                    DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                );

                Notification.Builder builder =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(app, READY_NOTIFICATION_CHANNEL)
                        : new Notification.Builder(app);

                builder
                    .setSmallIcon(app.getApplicationInfo().icon)
                    .setContentTitle("Android STT Bridge READY")
                    .setContentText("Русский STT готов к WakeWord • " + readyTime)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true);

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    builder.setPriority(Notification.PRIORITY_DEFAULT);
                }

                nm.notify(READY_NOTIFICATION_ID, builder.build());
                posted = true;
            }
        } catch (Throwable ignored) {
            posted = false;
        }

        setFlag(app, "readyNotificationPosted", posted);

        // Fallback for denied/disabled notification permission or channel issues.
        if (!posted) {
            try {
                Toast.makeText(
                    app,
                    "Android STT Bridge READY — можно говорить WakeWord",
                    Toast.LENGTH_LONG
                ).show();
            } catch (Throwable ignored) {}
        }
    }

    private enum LocalCommand {
        NONE,
        COLLAPSE,
        EXPAND,
        CLOSE
    }

    private static LocalCommand classifyLocalCommand(String raw) {
        String text = normalizeCommand(raw);
        String payload = stripBettyLead(text);

        if (matchesCommandSuffix(payload, "вернись в кристалл") ||
            matchesCommandSuffix(payload, "вернись в кристал") ||
            (wordCount(payload) <= 5 &&
             payload.contains("верн") &&
             payload.contains("кристал"))) {
            return LocalCommand.CLOSE;
        }

        if (matchesCommandSuffix(payload, "в малую форму") ||
            (wordCount(payload) <= 4 &&
             payload.contains("мал") &&
             payload.contains("форм"))) {
            return LocalCommand.COLLAPSE;
        }

        if (matchesCommandSuffix(payload, "выходи") ||
            (wordCount(payload) <= 2 &&
             payload.startsWith("выход"))) {
            return LocalCommand.EXPAND;
        }

        return LocalCommand.NONE;
    }

    private static String stripBettyLead(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] parts = text.split(" ", 2);
        if (parts.length > 0 && isBettyToken(parts[0])) {
            return parts.length > 1 ? parts[1].trim() : "";
        }
        return text;
    }

    private static boolean isBettyToken(String token) {
        if (token == null) return false;
        return token.equals("бетти") ||
            token.equals("бети") ||
            token.equals("петти") ||
            token.equals("пети") ||
            token.equals("метти") ||
            token.equals("мети");
    }

    private static boolean isCommandPrefixCandidate(String raw) {
        String text = normalizeCommand(raw);
        if (text.isEmpty()) return false;

        String payload = stripBettyLead(text);
        boolean hadBettyLead = !payload.equals(text);

        if (hadBettyLead && payload.isEmpty()) return true;
        if (wordCount(payload) > 4) return false;

        return payload.startsWith("выход") ||
            payload.equals("в") ||
            payload.startsWith("в мал") ||
            payload.contains("малую") ||
            payload.contains("форм") ||
            payload.startsWith("вер") ||
            payload.contains("кристал");
    }

    private static void setPendingCommand(String raw) {
        pendingCommandText = normalizeCommand(raw);
        pendingCommandUntilElapsedMs =
            SystemClock.elapsedRealtime() + COMMAND_ACCUMULATOR_WINDOW_MS;
    }

    private static String combinePendingWith(String raw) {
        String next = normalizeCommand(raw);
        String base = hasPendingCommand() ? pendingCommandText : "";
        String combined = base.isEmpty() ? next : (base + " " + next);
        pendingCommandText = normalizeCommand(combined);
        pendingCommandUntilElapsedMs =
            SystemClock.elapsedRealtime() + COMMAND_ACCUMULATOR_WINDOW_MS;
        return pendingCommandText;
    }

    private static boolean hasPendingCommand() {
        if (pendingCommandText == null || pendingCommandText.isEmpty()) {
            return false;
        }
        if (SystemClock.elapsedRealtime() > pendingCommandUntilElapsedMs) {
            clearPendingCommand();
            return false;
        }
        return true;
    }

    private static void clearPendingCommand() {
        pendingCommandText = "";
        pendingCommandUntilElapsedMs = 0L;
    }

    private static int wordCount(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    private static boolean matchesCommandSuffix(String text, String command) {
        if (text == null || command == null) return false;
        return text.equals(command) || text.endsWith(" " + command);
    }

    private static String normalizeCommand(String raw) {
        if (raw == null) return "";
        return raw
            .toLowerCase(Locale.ROOT)
            .replace('ё', 'е')
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static void handleLocalCommand(
        Context app,
        long generation,
        String sourceText,
        LocalCommand command
    ) {
        increment(app, "localCommands");
        markString(app, "lastLocalCommand", command.name());
        writeState(
            app,
            "LOCAL_COMMAND",
            sourceText,
            "",
            0,
            "",
            command.name()
        );

        waitingForAi = false;
        runtimeBusyObserved = false;

        switch (command) {
            case COLLAPSE:
                compactModeRequested = true;
                enterAvatarBall(app);
                MAIN.postDelayed(
                    () -> {
                        if (isGenerationActive(generation)) {
                            startWhenTtsIdle(app, generation, 0);
                        }
                    },
                    350
                );
                break;

            case EXPAND:
                compactModeRequested = false;
                exitAvatarBall(app);
                MAIN.postDelayed(
                    () -> {
                        if (isGenerationActive(generation)) {
                            startWhenTtsIdle(app, generation, 0);
                        }
                    },
                    350
                );
                break;

            case CLOSE:
                compactModeRequested = true;
                AvatarBallController.hide(app);
                setOperitFloatingVisible(true);
                activeWakeSession = false;
                sessionGeneration++;
                closeFloatingSession();
                break;

            default:
                break;
        }
    }

    private static void enterAvatarBall(Context app) {
        if (!avatarBallEnabled || !activeWakeSession) return;
        compactModeRequested = true;
        boolean hidden = setOperitFloatingVisible(false);
        AvatarBallController.show(app, () -> {
            MAIN.post(() -> {
                compactModeRequested = false;
                exitAvatarBall(app);
            });
        });
        AvatarBallController.setState(
            app,
            recognizerRunning ? "LISTENING" : (waitingForAi ? "THINKING" : "IDLE")
        );
        setFlag(app, "avatarBallLastHideSucceeded", hidden);
        setFlag(app, "avatarBallActive", true);
    }

    private static void exitAvatarBall(Context app) {
        AvatarBallController.hide(app);
        setOperitFloatingVisible(true);
        setFlag(app, "avatarBallActive", false);
    }

    private static boolean setOperitFloatingVisible(boolean visible) {
        try {
            Object service = getFloatingServiceInstance();
            if (service == null) return false;

            Field f = service.getClass().getDeclaredField("windowManager");
            f.setAccessible(true);
            Object manager = f.get(service);
            if (manager == null) return false;

            Method method =
                manager.getClass().getMethod("setFloatingWindowVisible", boolean.class);
            method.invoke(manager, visible);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean closeFloatingSession() {
        try {
            Object service = getFloatingServiceInstance();
            if (service == null) return false;
            Method method = service.getClass().getMethod("onClose");
            method.invoke(service);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWakeLaunched() {
        try {
            Object service = getFloatingServiceInstance();
            if (service == null) return false;
            Method m = service.getClass().getMethod("isWakeLaunched");
            Object value = m.invoke(service);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getFloatingServiceInstance() {
        try {
            Object companion = cachedFloatingCompanion;
            Method getter = cachedFloatingGetInstanceMethod;
            if (companion == null || getter == null) {
                Class<?> outer = Class.forName(FLOATING_SERVICE);
                Field companionField = outer.getField("Companion");
                companion = companionField.get(null);
                getter = companion.getClass().getMethod("getInstance");
                cachedFloatingCompanion = companion;
                cachedFloatingGetInstanceMethod = getter;
            }
            return getter.invoke(companion);
        } catch (Throwable ignored) {
            cachedFloatingCompanion = null;
            cachedFloatingGetInstanceMethod = null;
            return null;
        }
    }

    private static boolean sendVoiceMessageToFloating(String text) {
        try {
            Object service = getFloatingServiceInstance();
            if (service == null) return false;

            Class<?> promptTypeClass = Class.forName(PROMPT_TYPE);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object voice = Enum.valueOf((Class<? extends Enum>) promptTypeClass.asSubclass(Enum.class), "VOICE");

            Method method = service.getClass().getMethod(
                "onSendMessage",
                String.class,
                promptTypeClass
            );
            method.invoke(service, text, voice);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void shutdownOperitSpeechProvider(Context context) {
        try {
            Object singleton = cachedSpeechFactorySingleton;
            Method reset = cachedSpeechFactoryResetMethod;
            if (singleton == null || reset == null) {
                Class<?> factory = Class.forName(SPEECH_FACTORY);
                Field instanceField = factory.getField("INSTANCE");
                singleton = instanceField.get(null);
                reset = factory.getMethod("resetInstance");
                cachedSpeechFactorySingleton = singleton;
                cachedSpeechFactoryResetMethod = reset;
            }
            reset.invoke(singleton);
        } catch (Throwable ignored) {
            cachedSpeechFactorySingleton = null;
            cachedSpeechFactoryResetMethod = null;
        }
    }

    private static boolean isOperitVoiceSpeaking(Context context) {
        try {
            Object voiceService = cachedVoiceService;
            Method isSpeaking = cachedVoiceIsSpeakingMethod;

            if (voiceService == null ||
                isSpeaking == null ||
                cachedVoiceGeneration != sessionGeneration) {
                Class<?> factory = Class.forName(VOICE_FACTORY);
                Field instanceField = factory.getField("INSTANCE");
                Object singleton = instanceField.get(null);

                Method getInstance = factory.getMethod("getInstance", Context.class);
                voiceService = getInstance.invoke(singleton, context);
                if (voiceService == null) return false;

                isSpeaking = voiceService.getClass().getMethod("isSpeaking");
                cachedVoiceService = voiceService;
                cachedVoiceIsSpeakingMethod = isSpeaking;
                cachedVoiceGeneration = sessionGeneration;
            }

            Object value = isSpeaking.invoke(voiceService);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            invalidateVoiceCache();
            return false;
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

    private static void markRuntimeStateIfChanged(Context context, String value) {
        String normalized = value == null ? "" : value;
        if (normalized.equals(lastPolledRuntimeState)) return;
        lastPolledRuntimeState = normalized;
        markString(context, "lastRuntimeState", normalized);
    }

    private static void invalidateVoiceCache() {
        cachedVoiceService = null;
        cachedVoiceIsSpeakingMethod = null;
        cachedVoiceGeneration = -1L;
    }

    private static JSONObject buildCurrentPerfJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("windowShownToFirstReadyMs", deltaMs(perfWindowShownAt, perfFirstReadyAt));
        o.put("windowShownToActivateMs", deltaMs(perfWindowShownAt, perfActivateAt));
        o.put("activateToStockReleaseStartMs", deltaMs(perfActivateAt, perfStockReleaseStartAt));
        o.put("stockReleaseCallMs", deltaMs(perfStockReleaseStartAt, perfStockReleaseEndAt));
        o.put(
            "stockReleaseEndToRecognizerStartMs",
            deltaMs(perfStockReleaseEndAt, perfFirstRecognizerStartAt)
        );
        o.put(
            "recognizerStartToFirstReadyMs",
            deltaMs(perfFirstRecognizerStartAt, perfFirstReadyAt)
        );
        o.put("speechEndToResultMs", deltaMs(perfSpeechEndAt, perfResultAt));
        o.put("resultToSendMs", deltaMs(perfResultAt, perfSendAt));
        o.put("sendToFirstAiBusyMs", deltaMs(perfSendAt, perfFirstAiBusyAt));
        o.put("sendToFirstTtsSpeakingMs", deltaMs(perfSendAt, perfFirstTtsSpeakingAt));
        o.put("sendToTurnCompleteMs", deltaMs(perfSendAt, perfTurnCompleteAt));
        o.put("ttsEndToNextReadyMs", deltaMs(perfTtsEndAt, perfNextReadyAt));
        o.put("turnCompleteToNextReadyMs", deltaMs(perfTurnCompleteAt, perfNextReadyAt));
        o.put("recognizerCreateCount", perfRecognizerCreateCount);
        return o;
    }

    private static void archivePerfSampleIfUseful() {
        if (perfWindowShownAt <= 0L || perfFirstReadyAt <= 0L) return;
        synchronized (perfHistory) {
            perfHistory.add(new PerfSample());
            while (perfHistory.size() > PERF_HISTORY_LIMIT) {
                perfHistory.remove(0);
            }
        }
    }

    private static void resetPerfSession() {
        perfWindowShownAt = 0L;
        perfActivateAt = 0L;
        perfStockReleaseStartAt = 0L;
        perfStockReleaseEndAt = 0L;
        perfFirstRecognizerStartAt = 0L;
        perfFirstReadyAt = 0L;
        perfSpeechEndAt = 0L;
        perfResultAt = 0L;
        perfSendAt = 0L;
        perfFirstAiBusyAt = 0L;
        perfFirstTtsSpeakingAt = 0L;
        perfTtsEndAt = 0L;
        perfTurnCompleteAt = 0L;
        perfNextReadyAt = 0L;
        perfRecognizerCreateCount = 0L;
        perfSawTtsSpeaking = false;
    }

    private static long deltaMs(long start, long end) {
        if (start <= 0L || end <= 0L || end < start) return -1L;
        return end - start;
    }

    private static void increment(Context context, String key) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int value = p.getInt(key, 0);
        p.edit().putInt(key, value + 1).apply();
    }

    private static void mark(Context context, String key, long value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, value)
            .apply();
    }

    private static void markString(Context context, String key, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value == null ? "" : value)
            .apply();
    }

    private static void setFlag(Context context, String key, boolean value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply();
    }

    private static void writeState(
        Context context,
        String state,
        String text,
        String partial,
        int errorCode,
        String error,
        String lastEvent
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("state", state == null ? "" : state)
            .putString("lastEvent", lastEvent == null ? "" : lastEvent)
            .putString("lastText", text == null || text.isEmpty()
                ? context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastText", "")
                : text)
            .putString("partial", partial == null ? "" : partial)
            .putInt("errorCode", errorCode)
            .putString("error", error == null ? "" : error)
            .putLong("updatedAtEpochMs", System.currentTimeMillis())
            .apply();
    }

    private static String describe(Throwable t) {
        if (t == null) return "";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
