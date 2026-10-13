package org.telegram.messenger;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.telegram.ui.LaunchActivity;
import java.util.Map;
import java.util.UUID;

/** Local, inexact alarms, bound to the account owner rather than a reusable account slot. */
public final class LunagramMessageReminders {
    static final String ACTION = "org.telegram.messenger.LUNAGRAM_MESSAGE_REMINDER";
    private static final String PREFIX = "reminder_", CHANNEL = "lunagram_message_reminders";
    private static final String OWNER_EXTRA = "lunagramReminderOwner";
    private static final int MAX_PENDING = 128;
    private LunagramMessageReminders() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("lunagram_message_reminders", Context.MODE_PRIVATE);
    }
    private static boolean currentOwner(LunagramReminderRef reference) {
        return reference != null && reference.account < UserConfig.MAX_ACCOUNT_COUNT
            && reference.belongsTo(reference.account, UserConfig.getInstance(reference.account).getClientUserId());
    }
    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }

    private static NotificationManager notificationManager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
    private static boolean notificationsAvailable(Context context, boolean createChannel) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        NotificationManager manager = notificationManager(context);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null && createChannel) {
                channel = new NotificationChannel(CHANNEL, LocaleController.getString(R.string.LunagramReminderTitle), NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(LocaleController.getString(R.string.LunagramReminderNotification));
                manager.createNotificationChannel(channel);
                channel = manager.getNotificationChannel(CHANNEL);
            }
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    private static PendingIntent alarmIntent(Context context, String id, long whenMillis) {
        Intent intent = new Intent(context, LunagramMessageReminderReceiver.class).setAction(ACTION)
            .setData(Uri.parse("lunagram-reminder://alarm/" + id)).putExtra("reminder_id", id).putExtra("reminder_when", whenMillis);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void arm(Context context, String id, LunagramReminderRef reference) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) throw new IllegalStateException("Alarm service unavailable");
        long trigger = Math.max(reference.whenMillis, System.currentTimeMillis() + 1000);
        PendingIntent operation = alarmIntent(context, id, reference.whenMillis);
        if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation);
        else manager.set(AlarmManager.RTC_WAKEUP, trigger, operation);
    }
    private static void disarm(Context context, String id, LunagramReminderRef reference) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent operation = alarmIntent(context, id, reference == null ? 0 : reference.whenMillis);
        if (manager != null) manager.cancel(operation);
        operation.cancel();
        NotificationManager notifications = notificationManager(context);
        if (notifications != null) notifications.cancel(PREFIX + id, 0);
    }

    public static synchronized boolean schedule(int account, long dialogId, int messageId, long whenMillis) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || dialogId == 0
            || DialogObject.isFolderDialogId(dialogId) || messageId == 0 || whenMillis <= System.currentTimeMillis()) return false;
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (owner <= 0) return false;
        if (!LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS)) return false;
        SharedPreferences preferences = preferences(context);
        String id = null, previous = null;
        int count = 0;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            LunagramReminderRef reference = entry.getValue() instanceof String ? LunagramReminderRef.decode((String) entry.getValue()) : null;
            String storedId = entry.getKey().substring(PREFIX.length());
            if (!currentOwner(reference) || !validId(storedId)) {
                preferences.edit().remove(entry.getKey()).commit();
                if (validId(storedId)) disarm(context, storedId, reference);
                continue;
            }
            count++;
            if (reference.belongsTo(account, owner) && reference.dialogId == dialogId && reference.messageId == messageId) {
                id = entry.getKey().substring(PREFIX.length()); previous = (String) entry.getValue();
            }
        }
        if ((id == null && count >= MAX_PENDING) || (id != null && !validId(id))) return false;
        if (id == null) id = UUID.randomUUID().toString();
        String key = PREFIX + id;
        try {
            if (!notificationsAvailable(context, true)) return false;
            if (owner != UserConfig.getInstance(account).getClientUserId()
                    || !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS)) return false;
            LunagramReminderRef reference = new LunagramReminderRef(account, owner, dialogId, messageId, whenMillis);
            if (!preferences.edit().putString(key, reference.encode()).commit()) return false;
            try { arm(context, id, reference); }
            catch (RuntimeException schedulingFailed) {
                SharedPreferences.Editor rollback = preferences.edit();
                if (previous == null) rollback.remove(key); else rollback.putString(key, previous);
                rollback.commit();
                if (previous == null) disarm(context, id, reference);
                else arm(context, id, LunagramReminderRef.decode(previous));
                throw schedulingFailed;
            }
            NotificationManager notifications = notificationManager(context);
            if (notifications != null) notifications.cancel(PREFIX + id, 0);
            return true;
        } catch (RuntimeException error) { FileLog.e(error); return false; }
    }

    public static synchronized boolean cancel(int account, long dialogId, int messageId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        long owner = UserConfig.getInstance(account).getClientUserId();
        SharedPreferences preferences = preferences(context);
        boolean removed = false;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            LunagramReminderRef reference = entry.getValue() instanceof String ? LunagramReminderRef.decode((String) entry.getValue()) : null;
            if (!entry.getKey().startsWith(PREFIX) || reference == null || !reference.belongsTo(account, owner)
                || reference.dialogId != dialogId || reference.messageId != messageId) continue;
            String id = entry.getKey().substring(PREFIX.length());
            if (!validId(id)) continue;
            if (preferences.edit().remove(entry.getKey()).commit()) { disarm(context, id, reference); removed = true; }
        }
        return removed;
    }

    /** Re-arm after boot/package update and prune refs that no longer belong to logged-in owners. */
    public static synchronized void restore(Context context) {
        if (context == null) return;
        SharedPreferences preferences = preferences(context);
        int count = 0;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            String id = entry.getKey().substring(PREFIX.length());
            LunagramReminderRef reference = entry.getValue() instanceof String ? LunagramReminderRef.decode((String) entry.getValue()) : null;
            if (!validId(id) || !currentOwner(reference) || count++ >= MAX_PENDING) {
                preferences.edit().remove(entry.getKey()).commit();
                if (validId(id)) disarm(context, id, reference);
                continue;
            }
            try {
                if (reference.whenMillis <= System.currentTimeMillis()
                        || !LunagramSubscriptionAccess.canUse(reference.account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS)) disarm(context, id, reference);
                else if (notificationsAvailable(context, false)) arm(context, id, reference);
            }
            catch (RuntimeException error) { FileLog.e(error); }
        }
    }

    static synchronized void deliver(Context context, Intent alarm) {
        String id = alarm.getStringExtra("reminder_id");
        if (!validId(id)) return;
        SharedPreferences preferences = preferences(context);
        LunagramReminderRef reference = LunagramReminderRef.decode(preferences.getString(PREFIX + id, null));
        if (!currentOwner(reference)) {
            preferences.edit().remove(PREFIX + id).commit(); disarm(context, id, reference); return;
        }
        if (reference.whenMillis != alarm.getLongExtra("reminder_when", 0)) return;
        if (!LunagramSubscriptionAccess.canUse(reference.account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS)) {
            disarm(context, id, reference); return;
        }
        try {
            if (System.currentTimeMillis() + 1000 < reference.whenMillis) { arm(context, id, reference); return; }
            if (!notificationsAvailable(context, true)) return;
            Intent open = new Intent(context, LaunchActivity.class).setAction("com.tmessages.openchat.lunagram.reminder." + id)
                .setData(Uri.parse("lunagram-reminder://message/" + id)).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("currentAccount", reference.account).putExtra(OWNER_EXTRA, reference.owner).putExtra("message_id", reference.messageId);
            if (DialogObject.isEncryptedDialog(reference.dialogId)) open.putExtra("encId", DialogObject.getEncryptedChatId(reference.dialogId));
            else if (reference.dialogId < 0) open.putExtra("chatId", -reference.dialogId);
            else open.putExtra("userId", reference.dialogId);
            PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.notification).setContentTitle(LocaleController.getString(R.string.AppName))
                .setContentText(LocaleController.getString(R.string.LunagramReminderNotification)).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setWhen(reference.whenMillis).setContentIntent(content);
            notificationManager(context).notify(PREFIX + id, 0, notification.build());
            preferences.edit().remove(PREFIX + id).commit();
        } catch (RuntimeException error) { FileLog.e(error); }
    }

    /** Suspend alarms without erasing their references; renewal re-arms only future reminders. */
    public static synchronized void onAccessChanged(int account) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        long owner = UserConfig.getInstance(account).getClientUserId();
        boolean allowed = LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.MESSAGE_REMINDERS);
        for (Map.Entry<String, ?> entry : preferences(context).getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) continue;
            String id = entry.getKey().substring(PREFIX.length());
            LunagramReminderRef reference = entry.getValue() instanceof String ? LunagramReminderRef.decode((String) entry.getValue()) : null;
            if (!validId(id) || reference == null || !reference.belongsTo(account, owner)) continue;
            try {
                if (!allowed || reference.whenMillis <= System.currentTimeMillis()) disarm(context, id, reference);
                else if (notificationsAvailable(context, false)) arm(context, id, reference);
            } catch (RuntimeException error) { FileLog.e(error); }
        }
    }

    /** Call before LaunchActivity switches account, including when replaying an intent after unlocking. */
    public static boolean isValidOpenIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(OWNER_EXTRA)) return true;
        int account = intent.getIntExtra("currentAccount", -1);
        long owner = intent.getLongExtra(OWNER_EXTRA, 0);
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && owner > 0
            && UserConfig.getInstance(account).getClientUserId() == owner;
    }
}
