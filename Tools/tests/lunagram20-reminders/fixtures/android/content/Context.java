package android.content;
import android.app.AlarmManager;
import android.app.NotificationManager;
import java.util.HashMap;
import java.util.Map;
public class Context {
 public static final int MODE_PRIVATE=0; public static final String NOTIFICATION_SERVICE="notification", ALARM_SERVICE="alarm";
 public boolean permission=true, notificationsEnabled=true, commitFails;
 public final AlarmManager alarms=new AlarmManager(); public final NotificationManager notifications=new NotificationManager();
 public final MemoryPreferences preferences=new MemoryPreferences();
 public Object getSystemService(String service){return ALARM_SERVICE.equals(service)?alarms:NOTIFICATION_SERVICE.equals(service)?notifications:null;}
 public int checkSelfPermission(String permission){return this.permission?0:-1;}
 public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
 public final class MemoryPreferences implements SharedPreferences {
  public final Map<String,String> values=new HashMap<>();
  public Map<String,?> getAll(){return new HashMap<>(values);}
  public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){
   final Map<String,String> changes=new HashMap<>();
   public Editor putString(String key,String value){changes.put(key,value);return this;}
   public Editor remove(String key){changes.put(key,null);return this;}
   public boolean commit(){if(commitFails)return false;changes.forEach((key,value)->{if(value==null)values.remove(key);else values.put(key,value);});return true;}
  };}
 }
}
