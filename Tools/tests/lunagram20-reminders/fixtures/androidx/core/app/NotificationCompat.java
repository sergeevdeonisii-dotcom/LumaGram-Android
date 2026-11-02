package androidx.core.app;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
public final class NotificationCompat {
 public static final String CATEGORY_REMINDER="reminder";public static final int VISIBILITY_PRIVATE=0;
 public static final class Builder {
  private final Notification notification=new Notification();
  public Builder(Context context,String channel){} public Builder setSmallIcon(int icon){return this;}
  public Builder setContentTitle(String value){notification.title=value;return this;}
  public Builder setContentText(String value){notification.text=value;return this;}
  public Builder setAutoCancel(boolean value){return this;} public Builder setCategory(String value){return this;}
  public Builder setVisibility(int value){notification.visibility=value;return this;} public Builder setWhen(long value){return this;}
  public Builder setContentIntent(PendingIntent value){notification.content=value;return this;} public Notification build(){return notification;}
 }
}
