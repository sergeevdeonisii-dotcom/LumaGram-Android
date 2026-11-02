package androidx.core.app;
import android.content.Context;
public final class NotificationManagerCompat {
 private final Context context; private NotificationManagerCompat(Context context){this.context=context;}
 public static NotificationManagerCompat from(Context context){return new NotificationManagerCompat(context);}
 public boolean areNotificationsEnabled(){return context.notificationsEnabled;}
}
