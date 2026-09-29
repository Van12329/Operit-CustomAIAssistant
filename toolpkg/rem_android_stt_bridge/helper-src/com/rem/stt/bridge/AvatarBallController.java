package com.rem.stt.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

final class AvatarBallController {
    private static final String BRIDGE_PREFS = "rem_android_stt_bridge";
    private static final String AVATAR_PREFS = "avatar_preferences";
    private static final String KEY_CONFIGS = "avatar_configs";
    private static final String KEY_SETTINGS = "avatar_settings";

    private static WindowManager windowManager;
    private static WindowManager.LayoutParams params;
    private static FrameLayout root;
    private static ImageView image;
    private static Drawable currentDrawable;
    private static Runnable tapHandler;
    private static boolean shown = false;
    private static String currentState = "IDLE";
    private static String currentAsset = "";

    private AvatarBallController() {}

    static boolean isShown() {
        return shown;
    }

    static String getCurrentAsset() {
        return currentAsset == null ? "" : currentAsset;
    }

    static void show(Context context, Runnable onTap) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        tapHandler = onTap;

        if (shown && root != null) {
            setState(app, currentState);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(app)) {
            shown = false;
            return;
        }

        try {
            windowManager =
                (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return;

            int size = dp(app, 112);
            int margin = dp(app, 18);

            root = new FrameLayout(app);
            root.setClipToOutline(true);
            root.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            if (Build.VERSION.SDK_INT >= 21) root.setElevation(dp(app, 10));

            image = new ImageView(app);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout.LayoutParams imageLp =
                new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                );
            int imagePad = dp(app, 4);
            image.setPadding(imagePad, imagePad, imagePad, imagePad);
            root.addView(image, imageLp);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

            params = new WindowManager.LayoutParams(
                size,
                size,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;

            SharedPreferences p =
                app.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE);
            int savedX = p.getInt("avatarBallX", Integer.MIN_VALUE);
            int savedY = p.getInt("avatarBallY", Integer.MIN_VALUE);
            if (savedX == Integer.MIN_VALUE) {
                int screenWidth = app.getResources().getDisplayMetrics().widthPixels;
                params.x = Math.max(margin, screenWidth - size - margin);
            } else {
                params.x = savedX;
            }
            params.y = savedY == Integer.MIN_VALUE ? dp(app, 180) : savedY;

            installTouchHandler(app);

            windowManager.addView(root, params);
            shown = true;
            setState(app, currentState);
        } catch (Throwable ignored) {
            shown = false;
            safeRemove();
        }
    }

    static void hide(Context context) {
        safeRemove();
        shown = false;
    }

    static void setState(Context context, String state) {
        if (context == null) return;
        currentState = state == null ? "IDLE" : state.toUpperCase(Locale.ROOT);
        if (!shown || root == null || image == null) return;

        final Context app = context.getApplicationContext();
        updateFrame(app, currentState);

        String avatarPath = resolveAvatarPath(app, currentState);
        if (avatarPath == null || avatarPath.isEmpty()) {
            currentAsset = "";
            stopCurrentAnimation();
            try {
                image.setImageDrawable(app.getApplicationInfo().loadIcon(app.getPackageManager()));
            } catch (Throwable ignored) {}
            return;
        }

        if (avatarPath.equals(currentAsset) && image.getDrawable() != null) {
            startDrawableIfAnimated(image.getDrawable());
            return;
        }

        Drawable decoded = decodeDrawable(avatarPath);
        if (decoded == null) {
            currentAsset = "";
            try {
                image.setImageDrawable(app.getApplicationInfo().loadIcon(app.getPackageManager()));
            } catch (Throwable ignored) {}
            return;
        }

        stopCurrentAnimation();
        currentDrawable = decoded;
        currentAsset = avatarPath;
        image.setImageDrawable(decoded);
        startDrawableIfAnimated(decoded);
    }

    private static void updateFrame(Context app, String state) {
        int stroke;
        if ("LISTENING".equals(state)) {
            stroke = Color.rgb(70, 145, 255);
        } else if ("THINKING".equals(state)) {
            stroke = Color.rgb(179, 110, 255);
        } else if ("SPEAKING".equals(state)) {
            stroke = Color.rgb(65, 210, 160);
        } else {
            stroke = Color.rgb(150, 150, 160);
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(210, 20, 20, 24));
        bg.setStroke(dp(app, 3), stroke);
        root.setBackground(bg);
    }

    private static void installTouchHandler(final Context app) {
        root.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            int startX;
            int startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (params == null || windowManager == null) return false;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(app, 6) || Math.abs(dy) > dp(app, 6)) {
                            moved = true;
                        }
                        params.x = Math.max(0, startX + Math.round(dx));
                        params.y = Math.max(0, startY + Math.round(dy));
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (Throwable ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        app.getSharedPreferences(BRIDGE_PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putInt("avatarBallX", params.x)
                            .putInt("avatarBallY", params.y)
                            .apply();

                        if (event.getActionMasked() == MotionEvent.ACTION_UP && !moved) {
                            Runnable tap = tapHandler;
                            if (tap != null) tap.run();
                        }
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    private static String resolveAvatarPath(Context app, String state) {
        try {
            SharedPreferences p =
                app.getSharedPreferences(AVATAR_PREFS, Context.MODE_PRIVATE);

            String settingsRaw = p.getString(KEY_SETTINGS, null);
            String configsRaw = p.getString(KEY_CONFIGS, null);
            if (settingsRaw == null || configsRaw == null) return null;

            JSONObject settings = new JSONObject(settingsRaw);
            if (!settings.optBoolean("isVoiceCallAvatarEnabled", false)) return null;

            String currentId = settings.optString("currentAvatarId", "");
            if (currentId.isEmpty()) return null;

            JSONArray configs = new JSONArray(configsRaw);
            for (int i = 0; i < configs.length(); i++) {
                JSONObject cfg = configs.optJSONObject(i);
                if (cfg == null) continue;
                if (!currentId.equals(cfg.optString("id", ""))) continue;
                if (!"WEBP".equalsIgnoreCase(cfg.optString("type", ""))) return null;

                JSONObject data = cfg.optJSONObject("data");
                if (data == null) return null;
                String basePath = data.optString("basePath", "");
                if (basePath.isEmpty()) return null;

                String chosen = chooseWebpFile(data, state);
                if (chosen == null || chosen.isEmpty()) return null;

                File f = new File(basePath, chosen);
                if (f.isFile()) return f.getAbsolutePath();
                return null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String chooseWebpFile(JSONObject data, String state) {
        String emotion = "IDLE";
        if ("LISTENING".equals(state) || "SPEAKING".equals(state)) {
            emotion = "LISTENING";
        } else if ("THINKING".equals(state)) {
            emotion = "THINKING";
        }

        JSONObject explicit = data.optJSONObject("emotionToFileMap");
        if (explicit == null) explicit = data.optJSONObject("emotionAnimationMapping");
        if (explicit != null) {
            String mapped = explicit.optString(emotion, "");
            if (!mapped.isEmpty()) return mapped;
            if ("SPEAKING".equals(state)) {
                mapped = explicit.optString("HAPPY", "");
                if (!mapped.isEmpty()) return mapped;
            }
            mapped = explicit.optString("IDLE", "");
            if (!mapped.isEmpty()) return mapped;
        }

        JSONArray files = data.optJSONArray("webpFiles");
        if (files == null || files.length() == 0) return null;

        String[] aliases;
        if ("LISTENING".equals(state)) {
            aliases = new String[]{"listening", "talking", "speak", "speaking", "chat"};
        } else if ("SPEAKING".equals(state)) {
            aliases = new String[]{"speaking", "talking", "speak", "happy", "chat"};
        } else if ("THINKING".equals(state)) {
            aliases = new String[]{"thinking", "think", "loading"};
        } else {
            aliases = new String[]{"idle", "default", "normal", "standby"};
        }

        for (String alias : aliases) {
            for (int i = 0; i < files.length(); i++) {
                String name = files.optString(i, "");
                if (name.isEmpty()) continue;
                String base = new File(name).getName();
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                if (alias.equalsIgnoreCase(base)) return name;
            }
        }

        for (int i = 0; i < files.length(); i++) {
            String name = files.optString(i, "");
            if (name.toLowerCase(Locale.ROOT).endsWith(".webp")) return name;
        }
        return null;
    }

    private static Drawable decodeDrawable(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(file);
                return ImageDecoder.decodeDrawable(source);
            }
            return Drawable.createFromPath(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void startDrawableIfAnimated(Drawable drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            drawable instanceof AnimatedImageDrawable) {
            try {
                ((AnimatedImageDrawable) drawable).setRepeatCount(
                    AnimatedImageDrawable.REPEAT_INFINITE
                );
                ((AnimatedImageDrawable) drawable).start();
            } catch (Throwable ignored) {}
        }
    }

    private static void stopCurrentAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            currentDrawable instanceof AnimatedImageDrawable) {
            try {
                ((AnimatedImageDrawable) currentDrawable).stop();
            } catch (Throwable ignored) {}
        }
        currentDrawable = null;
    }

    private static void safeRemove() {
        stopCurrentAnimation();
        if (windowManager != null && root != null) {
            try {
                windowManager.removeViewImmediate(root);
            } catch (Throwable ignored) {}
        }
        root = null;
        image = null;
        params = null;
        windowManager = null;
        currentAsset = "";
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
