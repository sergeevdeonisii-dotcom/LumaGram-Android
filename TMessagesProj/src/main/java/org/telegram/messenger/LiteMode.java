package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.math.MathUtils;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

public class LiteMode {

    public static final int FLAG_ANIMATED_STICKERS_KEYBOARD = 1;
    public static final int FLAG_ANIMATED_STICKERS_CHAT = 2;
    public static final int FLAGS_ANIMATED_STICKERS = FLAG_ANIMATED_STICKERS_KEYBOARD | FLAG_ANIMATED_STICKERS_CHAT;

    public static final int FLAG_ANIMATED_EMOJI_KEYBOARD_PREMIUM = 4;
    public static final int FLAG_ANIMATED_EMOJI_KEYBOARD_NOT_PREMIUM = 16384;
    public static final int FLAG_ANIMATED_EMOJI_KEYBOARD = FLAG_ANIMATED_EMOJI_KEYBOARD_PREMIUM | FLAG_ANIMATED_EMOJI_KEYBOARD_NOT_PREMIUM;
    public static final int FLAG_ANIMATED_EMOJI_REACTIONS_PREMIUM = 8;
    public static final int FLAG_ANIMATED_EMOJI_REACTIONS_NOT_PREMIUM = 8192;
    public static final int FLAG_ANIMATED_EMOJI_REACTIONS = FLAG_ANIMATED_EMOJI_REACTIONS_PREMIUM | FLAG_ANIMATED_EMOJI_REACTIONS_NOT_PREMIUM;
    public static final int FLAG_ANIMATED_EMOJI_CHAT_PREMIUM = 16;
    public static final int FLAG_ANIMATED_EMOJI_CHAT_NOT_PREMIUM = 4096;
    public static final int FLAG_ANIMATED_EMOJI_CHAT = FLAG_ANIMATED_EMOJI_CHAT_PREMIUM | FLAG_ANIMATED_EMOJI_CHAT_NOT_PREMIUM;
    public static final int FLAGS_ANIMATED_EMOJI = FLAG_ANIMATED_EMOJI_KEYBOARD | FLAG_ANIMATED_EMOJI_REACTIONS | FLAG_ANIMATED_EMOJI_CHAT;

    public static final int FLAG_CHAT_BACKGROUND = 32;
    public static final int FLAG_CHAT_FORUM_TWOCOLUMN = 64;
    public static final int FLAG_CHAT_SPOILER = 128;
    public static final int FLAG_CHAT_BLUR = 256;
    public static final int FLAG_CHAT_SCALE = 32768;
    public static final int FLAG_CHAT_THANOS = 65536;
    public static final int FLAG_LIQUID_GLASS = 1 << 18;
    public static final int FLAGS_CHAT = FLAG_CHAT_BACKGROUND | FLAG_CHAT_FORUM_TWOCOLUMN | FLAG_CHAT_SPOILER | FLAG_CHAT_BLUR | FLAG_CHAT_SCALE | FLAG_CHAT_THANOS;

    private static final String LIQUID_GLASS_ENABLED = "liquid_glass_enabled";
    private static final String LIQUID_GLASS_KEEP_IN_POWER_SAVER = "liquid_glass_keep_in_power_saver";
    private static final String LIQUID_GLASS_OPACITY = "liquid_glass_opacity";
    private static final String LIQUID_GLASS_INTENSITY = "liquid_glass_intensity";
    private static final String LIQUID_GLASS_INPUT_SIZE = "liquid_glass_input_size";
    private static final String LIQUID_GLASS_ADAPTIVE_COLOR = "liquid_glass_adaptive_color";
    private static final String LIQUID_GLASS_COLOR_STRENGTH = "liquid_glass_color_strength";
    private static final String LIQUID_GLASS_SEPARATE_COLORS = "liquid_glass_separate_colors";
    private static final String LIQUID_GLASS_COLOR_TRANSITION = "liquid_glass_color_transition";
    private static Boolean liquidGlassEnabled;
    private static Boolean liquidGlassKeepInPowerSaver;
    private static Boolean liquidGlassAdaptiveColor;
    private static Boolean liquidGlassSeparateColors;
    private static int liquidGlassOpacity = -1;
    private static int liquidGlassIntensity = -1;
    private static int liquidGlassInputSize = -1;
    private static int liquidGlassColorStrength = -1;
    private static int liquidGlassColorTransition = -1;

    public static final int FLAG_CALLS_ANIMATIONS = 512;
    public static final int FLAG_AUTOPLAY_VIDEOS = 1024;
    public static final int FLAG_AUTOPLAY_GIFS = 2048;
    public static final int FLAG_PARTICLES = 1 << 17;

    public static int PRESET_LOW = (
        FLAG_ANIMATED_EMOJI_CHAT_PREMIUM |
        FLAG_ANIMATED_EMOJI_KEYBOARD_PREMIUM |
        FLAG_ANIMATED_EMOJI_REACTIONS_PREMIUM |
        FLAG_AUTOPLAY_GIFS |
        FLAG_CHAT_THANOS |
        FLAG_PARTICLES
    ); // 198684
    public static int PRESET_MEDIUM = (
        FLAGS_ANIMATED_STICKERS |
        FLAG_ANIMATED_EMOJI_KEYBOARD_PREMIUM |
        FLAG_ANIMATED_EMOJI_REACTIONS_PREMIUM |
        FLAG_ANIMATED_EMOJI_CHAT |
        FLAG_CHAT_FORUM_TWOCOLUMN |
        FLAG_CALLS_ANIMATIONS |
        FLAG_AUTOPLAY_VIDEOS |
        FLAG_AUTOPLAY_GIFS |
        FLAG_CHAT_THANOS |
        FLAG_PARTICLES
    ); // 204383
    public static int PRESET_HIGH = (
        FLAGS_ANIMATED_STICKERS |
        FLAGS_ANIMATED_EMOJI |
        FLAG_CHAT_BACKGROUND |
        FLAG_CHAT_FORUM_TWOCOLUMN |
        FLAG_CHAT_SPOILER |
        FLAG_CHAT_BLUR |
        FLAG_CHAT_SCALE |
        FLAG_CHAT_THANOS |
        FLAG_CALLS_ANIMATIONS |
        FLAG_AUTOPLAY_VIDEOS |
        FLAG_AUTOPLAY_GIFS |
        FLAG_PARTICLES
    ); // 262143
    public static int PRESET_POWER_SAVER = 0;

    private static int BATTERY_LOW = 10;
    private static int BATTERY_MEDIUM = 10;
    private static int BATTERY_HIGH = 10;

    private static int powerSaverLevel;
    private static boolean lastPowerSaverApplied;

    private static int value;
    private static boolean loaded;

    public static int getValue() {
        return getValue(false);
    }

    public static int getValue(boolean ignorePowerSaving) {
        if (!loaded) {
            loadPreference();
        }
        if (!ignorePowerSaving && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (getBatteryLevel() <= powerSaverLevel && powerSaverLevel > 0) {
                if (!lastPowerSaverApplied) {
                    onPowerSaverApplied(lastPowerSaverApplied = true);
                }
                return PRESET_POWER_SAVER;
            }
            if (lastPowerSaverApplied) {
                onPowerSaverApplied(lastPowerSaverApplied = false);
            }
        }
        return value;
    }

    private static int lastBatteryLevelCached = -1;
    private static long lastBatteryLevelChecked;

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static int getBatteryLevel() {
        long time = 0;
        if (lastBatteryLevelCached < 0 || (time = System.currentTimeMillis()) - lastBatteryLevelChecked > 1000 * 12) {
            BatteryManager batteryManager = (BatteryManager) ApplicationLoader.applicationContext.getSystemService(Context.BATTERY_SERVICE);
            if (batteryManager != null) {
                lastBatteryLevelCached = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                lastBatteryLevelChecked = time;
            }
        }
        return lastBatteryLevelCached;
    }

    private static int preprocessFlag(int flag) {
        if ((flag & FLAG_ANIMATED_EMOJI_KEYBOARD) > 0) {
            flag = flag & ~FLAG_ANIMATED_EMOJI_KEYBOARD | (UserConfig.hasPremiumOnAccounts() ? FLAG_ANIMATED_EMOJI_KEYBOARD_PREMIUM : FLAG_ANIMATED_EMOJI_KEYBOARD_NOT_PREMIUM);
        }
        if ((flag & FLAG_ANIMATED_EMOJI_REACTIONS) > 0) {
            flag = flag & ~FLAG_ANIMATED_EMOJI_REACTIONS | (UserConfig.hasPremiumOnAccounts() ? FLAG_ANIMATED_EMOJI_REACTIONS_PREMIUM : FLAG_ANIMATED_EMOJI_REACTIONS_NOT_PREMIUM);
        }
        if ((flag & FLAG_ANIMATED_EMOJI_CHAT) > 0) {
            flag = flag & ~FLAG_ANIMATED_EMOJI_CHAT | (UserConfig.hasPremiumOnAccounts() ? FLAG_ANIMATED_EMOJI_CHAT_PREMIUM : FLAG_ANIMATED_EMOJI_CHAT_NOT_PREMIUM);
        }
        return flag;
    }

    public static boolean isEnabled(int flag) {
        if (flag == FLAG_LIQUID_GLASS) {
            if (!getLiquidGlassEnabled()) {
                return false;
            }
            // Keep this custom setting independent from Telegram's remotely
            // updated animation presets. Only the explicit battery option may
            // temporarily suppress it.
            return getLiquidGlassKeepInPowerSaver() || !isPowerSaverApplied();
        }
        if (flag == FLAG_CHAT_FORUM_TWOCOLUMN && AndroidUtilities.isTablet()) {
            // always enabled for tablets
            return true;
        }
        return (getValue() & preprocessFlag(flag)) > 0;
    }

    public static boolean isEnabledSetting(int flag) {
        if (flag == FLAG_LIQUID_GLASS) {
            return getLiquidGlassEnabled();
        }
        return (getValue(true) & flag) > 0;
    }

    public static boolean getLiquidGlassEnabled() {
        if (liquidGlassEnabled == null) {
            liquidGlassEnabled = MessagesController.getGlobalMainSettings().getBoolean(LIQUID_GLASS_ENABLED, true);
        }
        return liquidGlassEnabled;
    }

    private static void setLiquidGlassEnabled(boolean value) {
        liquidGlassEnabled = value;
        MessagesController.getGlobalMainSettings().edit().putBoolean(LIQUID_GLASS_ENABLED, value).apply();
    }

    public static boolean getLiquidGlassKeepInPowerSaver() {
        if (liquidGlassKeepInPowerSaver == null) {
            liquidGlassKeepInPowerSaver = MessagesController.getGlobalMainSettings().getBoolean(LIQUID_GLASS_KEEP_IN_POWER_SAVER, true);
        }
        return liquidGlassKeepInPowerSaver;
    }

    public static void setLiquidGlassKeepInPowerSaver(boolean value) {
        liquidGlassKeepInPowerSaver = value;
        MessagesController.getGlobalMainSettings().edit().putBoolean(LIQUID_GLASS_KEEP_IN_POWER_SAVER, value).apply();
    }

    public static int getLiquidGlassOpacityLevel() {
        if (liquidGlassOpacity < 0) {
            liquidGlassOpacity = MathUtils.clamp(MessagesController.getGlobalMainSettings().getInt(LIQUID_GLASS_OPACITY, 1), 0, 2);
        }
        return liquidGlassOpacity;
    }

    public static void setLiquidGlassOpacityLevel(int value) {
        liquidGlassOpacity = MathUtils.clamp(value, 0, 2);
        MessagesController.getGlobalMainSettings().edit().putInt(LIQUID_GLASS_OPACITY, liquidGlassOpacity).apply();
    }

    public static float applyLiquidGlassAlpha(float alpha) {
        return MathUtils.clamp(alpha + (getLiquidGlassOpacityLevel() - 1) * 0.10f, 0.35f, 0.95f);
    }

    public static int getLiquidGlassIntensityLevel() {
        if (liquidGlassIntensity < 0) {
            liquidGlassIntensity = MathUtils.clamp(MessagesController.getGlobalMainSettings().getInt(LIQUID_GLASS_INTENSITY, 1), 0, 2);
        }
        return liquidGlassIntensity;
    }

    public static void setLiquidGlassIntensityLevel(int value) {
        liquidGlassIntensity = MathUtils.clamp(value, 0, 2);
        MessagesController.getGlobalMainSettings().edit().putInt(LIQUID_GLASS_INTENSITY, liquidGlassIntensity).apply();
    }

    public static float getLiquidGlassIntensityScale() {
        switch (getLiquidGlassIntensityLevel()) {
            case 0: return 0.82f;
            case 2: return 1.18f;
            default: return 1.0f;
        }
    }

    public static int getLiquidGlassInputSizeLevel() {
        if (liquidGlassInputSize < 0) {
            liquidGlassInputSize = MathUtils.clamp(MessagesController.getGlobalMainSettings().getInt(LIQUID_GLASS_INPUT_SIZE, 0), 0, 2);
        }
        return liquidGlassInputSize;
    }

    public static void setLiquidGlassInputSizeLevel(int value) {
        liquidGlassInputSize = MathUtils.clamp(value, 0, 2);
        MessagesController.getGlobalMainSettings().edit().putInt(LIQUID_GLASS_INPUT_SIZE, liquidGlassInputSize).apply();
    }

    public static int getLiquidGlassInputHeightDp() {
        return 40 + getLiquidGlassInputSizeLevel() * 2;
    }

    public static boolean getLiquidGlassAdaptiveColorEnabled() {
        if (liquidGlassAdaptiveColor == null) {
            liquidGlassAdaptiveColor = MessagesController.getGlobalMainSettings().getBoolean(LIQUID_GLASS_ADAPTIVE_COLOR, true);
        }
        return liquidGlassAdaptiveColor;
    }

    public static void setLiquidGlassAdaptiveColorEnabled(boolean value) {
        liquidGlassAdaptiveColor = value;
        MessagesController.getGlobalMainSettings().edit().putBoolean(LIQUID_GLASS_ADAPTIVE_COLOR, value).apply();
    }

    public static int getLiquidGlassColorStrengthLevel() {
        if (liquidGlassColorStrength < 0) {
            liquidGlassColorStrength = MathUtils.clamp(MessagesController.getGlobalMainSettings().getInt(LIQUID_GLASS_COLOR_STRENGTH, 1), 0, 2);
        }
        return liquidGlassColorStrength;
    }

    public static void setLiquidGlassColorStrengthLevel(int value) {
        liquidGlassColorStrength = MathUtils.clamp(value, 0, 2);
        MessagesController.getGlobalMainSettings().edit().putInt(LIQUID_GLASS_COLOR_STRENGTH, liquidGlassColorStrength).apply();
    }

    public static float getLiquidGlassColorBlend() {
        switch (getLiquidGlassColorStrengthLevel()) {
            case 0: return 0.14f;
            case 2: return 0.36f;
            default: return 0.24f;
        }
    }

    public static boolean getLiquidGlassSeparateColors() {
        if (liquidGlassSeparateColors == null) {
            liquidGlassSeparateColors = MessagesController.getGlobalMainSettings().getBoolean(LIQUID_GLASS_SEPARATE_COLORS, true);
        }
        return liquidGlassSeparateColors;
    }

    public static void setLiquidGlassSeparateColors(boolean value) {
        liquidGlassSeparateColors = value;
        MessagesController.getGlobalMainSettings().edit().putBoolean(LIQUID_GLASS_SEPARATE_COLORS, value).apply();
    }

    public static int getLiquidGlassColorTransitionLevel() {
        if (liquidGlassColorTransition < 0) {
            liquidGlassColorTransition = MathUtils.clamp(MessagesController.getGlobalMainSettings().getInt(LIQUID_GLASS_COLOR_TRANSITION, 1), 0, 2);
        }
        return liquidGlassColorTransition;
    }

    public static void setLiquidGlassColorTransitionLevel(int value) {
        liquidGlassColorTransition = MathUtils.clamp(value, 0, 2);
        MessagesController.getGlobalMainSettings().edit().putInt(LIQUID_GLASS_COLOR_TRANSITION, liquidGlassColorTransition).apply();
    }

    public static int getLiquidGlassColorTransitionDuration() {
        if (!getLiquidGlassAdaptiveColorEnabled()) {
            return 0;
        }
        switch (getLiquidGlassColorTransitionLevel()) {
            case 0: return 140;
            case 2: return 420;
            default: return 260;
        }
    }

    public static void resetLiquidGlassSettings() {
        liquidGlassEnabled = true;
        liquidGlassKeepInPowerSaver = true;
        liquidGlassAdaptiveColor = true;
        liquidGlassSeparateColors = true;
        liquidGlassOpacity = 1;
        liquidGlassIntensity = 1;
        liquidGlassInputSize = 0;
        liquidGlassColorStrength = 1;
        liquidGlassColorTransition = 1;
        MessagesController.getGlobalMainSettings().edit()
            .remove(LIQUID_GLASS_ENABLED)
            .remove(LIQUID_GLASS_KEEP_IN_POWER_SAVER)
            .remove(LIQUID_GLASS_OPACITY)
            .remove(LIQUID_GLASS_INTENSITY)
            .remove(LIQUID_GLASS_INPUT_SIZE)
            .remove(LIQUID_GLASS_ADAPTIVE_COLOR)
            .remove(LIQUID_GLASS_COLOR_STRENGTH)
            .remove(LIQUID_GLASS_SEPARATE_COLORS)
            .remove(LIQUID_GLASS_COLOR_TRANSITION)
            .apply();
        setLiquidGlassEnabled(true);
    }

    public static void toggleFlag(int flag) {
        toggleFlag(flag, !isEnabled(flag));
    }

    public static void toggleFlag(int flag, boolean enabled) {
        if (flag == FLAG_LIQUID_GLASS) {
            setLiquidGlassEnabled(enabled);
            return;
        }
        setAllFlags(enabled ? getValue(true) | flag : getValue(true) & ~flag);
    }

    public static void setAllFlags(int flags) {
        // in settings it is already handled. would you handle it? 🫵
        // onFlagsUpdate(value, flags);
        value = flags;
        savePreference();
    }

    public static void updatePresets(TLRPC.TL_jsonObject json) {
        for (int i = 0; i < json.value.size(); ++i) {
            TLRPC.TL_jsonObjectValue kv = json.value.get(i);
            if ("settings_mask".equals(kv.key) && kv.value instanceof TLRPC.TL_jsonArray) {
                ArrayList<TLRPC.JSONValue> array = ((TLRPC.TL_jsonArray) kv.value).value;
                try {
                    PRESET_LOW = (int) ((TLRPC.TL_jsonNumber) array.get(0)).value & ~FLAG_LIQUID_GLASS;
                    PRESET_MEDIUM = (int) ((TLRPC.TL_jsonNumber) array.get(1)).value & ~FLAG_LIQUID_GLASS;
                    PRESET_HIGH = (int) ((TLRPC.TL_jsonNumber) array.get(2)).value & ~FLAG_LIQUID_GLASS;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            } else if ("battery_low".equals(kv.key) && kv.value instanceof TLRPC.TL_jsonArray) {
                ArrayList<TLRPC.JSONValue> array = ((TLRPC.TL_jsonArray) kv.value).value;
                try {
                    BATTERY_LOW = (int) ((TLRPC.TL_jsonNumber) array.get(0)).value;
                    BATTERY_MEDIUM = (int) ((TLRPC.TL_jsonNumber) array.get(1)).value;
                    BATTERY_HIGH = (int) ((TLRPC.TL_jsonNumber) array.get(2)).value;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        loadPreference();
    }

    public static void loadPreference() {
        int defaultValue = PRESET_HIGH, batteryDefaultValue = BATTERY_HIGH;
        if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW) {
            defaultValue = PRESET_LOW;
            batteryDefaultValue = BATTERY_LOW;
        } else if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_AVERAGE) {
            defaultValue = PRESET_MEDIUM;
            batteryDefaultValue = BATTERY_MEDIUM;
        }

        final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (!preferences.contains("lite_mode6")) {
            if (preferences.contains("lite_mode5")) {
                defaultValue = preferences.getInt("lite_mode5", defaultValue);
                defaultValue &=~ FLAG_LIQUID_GLASS;
                preferences.edit().putInt("lite_mode6", defaultValue).apply();
            } else if (preferences.contains("lite_mode4")) {
                defaultValue = preferences.getInt("lite_mode4", defaultValue);
                preferences.edit().putInt("lite_mode5", defaultValue).apply();
            } else if (preferences.contains("lite_mode3")) {
                defaultValue = preferences.getInt("lite_mode3", defaultValue);
                defaultValue |= FLAG_PARTICLES;
                preferences.edit().putInt("lite_mode5", defaultValue).apply();
            } else if (preferences.contains("lite_mode2")) {
                defaultValue = preferences.getInt("lite_mode2", defaultValue);
                defaultValue |= FLAG_CHAT_THANOS;
                preferences.edit().putInt("lite_mode3", defaultValue).apply();
            } else if (preferences.contains("lite_mode")) {
                defaultValue = preferences.getInt("lite_mode", defaultValue);
                if (defaultValue == 4095) {
                    defaultValue = PRESET_HIGH;
                }
            } else {
                if (preferences.contains("light_mode")) {
                    boolean prevLiteModeEnabled = (preferences.getInt("light_mode", SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW ? 1 : 0) & 1) > 0;
                    if (prevLiteModeEnabled) {
                        defaultValue = PRESET_LOW;
                    } else {
                        defaultValue = PRESET_HIGH;
                    }
                }
                // migrate settings
                if (preferences.contains("loopStickers")) {
                    boolean loopStickers = preferences.getBoolean("loopStickers", true);
                    if (loopStickers) {
                        defaultValue |= FLAG_ANIMATED_STICKERS_CHAT;
                    } else {
                        defaultValue &= ~FLAG_ANIMATED_STICKERS_CHAT;
                    }
                }
                if (preferences.contains("autoplay_video")) {
                    boolean autoplayVideo = preferences.getBoolean("autoplay_video", true) || preferences.getBoolean("autoplay_video_liteforce", false);
                    if (autoplayVideo) {
                        defaultValue |= FLAG_AUTOPLAY_VIDEOS;
                    } else {
                        defaultValue &= ~FLAG_AUTOPLAY_VIDEOS;
                    }
                }
                if (preferences.contains("autoplay_gif")) {
                    boolean autoplayGif = preferences.getBoolean("autoplay_gif", true);
                    if (autoplayGif) {
                        defaultValue |= FLAG_AUTOPLAY_GIFS;
                    } else {
                        defaultValue &= ~FLAG_AUTOPLAY_GIFS;
                    }
                }
                if (preferences.contains("chatBlur")) {
                    boolean chatBlur = preferences.getBoolean("chatBlur", true);
                    if (chatBlur) {
                        defaultValue |= FLAG_CHAT_BLUR;
                    } else {
                        defaultValue &= ~FLAG_CHAT_BLUR;
                    }
                }
            }
        }

        int prevValue = value;
        final int storedValue = preferences.getInt("lite_mode6", defaultValue);
        value = storedValue & ~FLAG_LIQUID_GLASS;
        if (storedValue != value) {
            preferences.edit().putInt("lite_mode6", value).apply();
        }
        if (loaded) {
            onFlagsUpdate(prevValue, value);
        }
        powerSaverLevel = preferences.getInt("lite_mode_battery_level", batteryDefaultValue);
        loaded = true;
    }

    public static void savePreference() {
        MessagesController.getGlobalMainSettings().edit().putInt("lite_mode6", value).putInt("lite_mode_battery_level", powerSaverLevel).apply();
    }

    public static int getPowerSaverLevel() {
        if (!loaded) {
            loadPreference();
        }
        return powerSaverLevel;
    }

    public static void setPowerSaverLevel(int value) {
        powerSaverLevel = MathUtils.clamp(value, 0, 100);
        savePreference();

        // check power saver applied
        getValue(false);
    }

    public static boolean isPowerSaverApplied() {
        getValue(false);
        return lastPowerSaverApplied;
    }

    private static void onPowerSaverApplied(boolean powerSaverApplied) {
        if (powerSaverApplied) {
            onFlagsUpdate(getValue(true), PRESET_POWER_SAVER);
        } else {
            onFlagsUpdate(PRESET_POWER_SAVER, getValue(true));
        }
        if (onPowerSaverAppliedListeners != null) {
            AndroidUtilities.runOnUIThread(() -> {
                Iterator<Utilities.Callback<Boolean>> i = onPowerSaverAppliedListeners.iterator();
                while (i.hasNext()) {
                    Utilities.Callback<Boolean> callback = i.next();
                    if (callback != null) {
                        callback.run(powerSaverApplied);
                    }
                }
            });
        }
    }

    private static void onFlagsUpdate(int oldValue, int newValue) {
        int changedFlags = ~oldValue & newValue;
        if ((changedFlags & FLAGS_ANIMATED_EMOJI) > 0) {
            AnimatedEmojiDrawable.updateAll();
        }
        if ((changedFlags & FLAG_CHAT_BACKGROUND) > 0) {
            SvgHelper.SvgDrawable.updateLiteValues();
        }
        if ((changedFlags & FLAG_CHAT_BACKGROUND) > 0) {
            Theme.reloadWallpaper(true);
        }
    }

    private static HashSet<Utilities.Callback<Boolean>> onPowerSaverAppliedListeners;
    public static void addOnPowerSaverAppliedListener(Utilities.Callback<Boolean> listener) {
        if (onPowerSaverAppliedListeners == null) {
            onPowerSaverAppliedListeners = new HashSet<>();
        }
        onPowerSaverAppliedListeners.add(listener);
    }

    public static void removeOnPowerSaverAppliedListener(Utilities.Callback<Boolean> listener) {
        if (onPowerSaverAppliedListeners != null) {
            onPowerSaverAppliedListeners.remove(listener);
        }
    }

    public static class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            lastBatteryLevelChecked = 0;
            getValue();
        }
    }
}
