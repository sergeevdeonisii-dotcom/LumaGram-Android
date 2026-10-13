package android.app;
public class NotificationChannel {
 public final String id; public int importance;
 public NotificationChannel(String id,String name,int importance){this.id=id;this.importance=importance;}
 public void setDescription(String value){} public int getImportance(){return importance;}
}
