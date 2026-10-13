package android.app;
import java.util.HashMap;
import java.util.Map;
public class NotificationManager {
 public static final int IMPORTANCE_NONE=0,IMPORTANCE_DEFAULT=3;
 public final Map<String,NotificationChannel> channels=new HashMap<>();
 public final Map<String,Notification> delivered=new HashMap<>();
 public NotificationChannel getNotificationChannel(String id){return channels.get(id);}
 public void createNotificationChannel(NotificationChannel value){channels.put(value.id,value);}
 public void notify(String tag,int id,Notification value){delivered.put(tag,value);}
 public void cancel(String tag,int id){delivered.remove(tag);}
}
