package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class LunagramMessageReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            && !LunagramMessageReminders.ACTION.equals(action)) return;
        try {
            ApplicationLoader.postInitApplication();
            if (LunagramMessageReminders.ACTION.equals(action)) LunagramMessageReminders.deliver(context, intent);
            else LunagramMessageReminders.restore(context);
        } catch (RuntimeException error) { FileLog.e(error); }
    }
}
