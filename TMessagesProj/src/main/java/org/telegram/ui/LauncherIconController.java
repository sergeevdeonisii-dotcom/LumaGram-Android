package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

public class LauncherIconController {
    private static final String RESTORED_ORIGINAL_ICON = "luma_original_icon_restored_100";

    public static void tryFixLauncherIconIfNeeded() {
        Context context = ApplicationLoader.applicationContext;
        SharedPreferences preferences = context.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
        if (!preferences.getBoolean(RESTORED_ORIGINAL_ICON, false)) {
            // Restore the old brand once. Other chosen icons survive the update; Black Hole stays
            // available and can be selected again without being reset on every process launch.
            try {
                if (isEnabled(LauncherIcon.BLACK_HOLE)) {
                    setIcon(LauncherIcon.DEFAULT);
                }
                preferences.edit().putBoolean(RESTORED_ORIGINAL_ICON, true).apply();
            } catch (RuntimeException error) {
                // A cosmetic migration must not prevent opening the app on an OEM launcher.
                FileLog.e(error);
            }
        }
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        // Keep a launcher entry enabled throughout the switch.
        pm.setComponentEnabledSetting(icon.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (LauncherIcon i : LauncherIcon.values()) {
            if (i != icon) {
                pm.setComponentEnabledSetting(i.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        }
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.drawable.luma_launcher_background, R.drawable.icon_plane, R.string.AppIconDefault),
        BLACK_HOLE("BlackHoleIcon", R.drawable.bhg_icon_blackhole_background, R.drawable.bhg_icon_blackhole_foreground, R.string.AppIconBlackHole),
        GRAPHITE("LumaGraphiteIcon", R.drawable.luma_icon_graphite_background, R.drawable.luma_icon_silver_foreground, R.string.AppIconLumaGraphite),
        NAVY("LumaNavyIcon", R.drawable.luma_icon_navy_background, R.drawable.luma_icon_silver_foreground, R.string.AppIconLumaNavy),
        SILVER("LumaSilverIcon", R.drawable.luma_icon_silver_background, R.drawable.luma_icon_silver_foreground, R.string.AppIconLumaSilver),
        GARNET("LumaGarnetIcon", R.drawable.luma_icon_garnet_background, R.drawable.luma_icon_gold_foreground, R.string.AppIconLumaGarnet),
        VIOLET("LumaVioletIcon", R.drawable.luma_icon_violet_background, R.drawable.luma_icon_silver_foreground, R.string.AppIconLumaViolet),
        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconAqua),
        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium, true),
        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo, true),
        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconNox, true);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
