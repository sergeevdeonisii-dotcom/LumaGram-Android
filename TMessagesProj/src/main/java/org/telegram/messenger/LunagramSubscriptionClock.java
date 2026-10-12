package org.telegram.messenger;

import android.os.Build;
import android.provider.Settings;

/** A wall-clock change must never extend a paid term. */
final class LunagramSubscriptionClock {
    static int bootId() {
        if (Build.VERSION.SDK_INT < 24 || ApplicationLoader.applicationContext == null) return -1;
        try {
            return Settings.Global.getInt(ApplicationLoader.applicationContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (Exception unavailable) { return -1; }
    }
}
