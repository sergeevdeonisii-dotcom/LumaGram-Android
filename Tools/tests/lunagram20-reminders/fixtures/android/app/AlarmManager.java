package android.app;
import java.util.LinkedHashMap;
import java.util.Map;
public class AlarmManager {
 public static final int RTC_WAKEUP=0;
 public boolean failNext; public int idleCalls,normalCalls;
 public final Map<String,PendingIntent> operations=new LinkedHashMap<>();
 public void setAndAllowWhileIdle(int type,long time,PendingIntent operation){idleCalls++;set(type,time,operation);}
 public void set(int type,long time,PendingIntent operation){normalCalls++;if(failNext){failNext=false;throw new IllegalStateException("test alarm rejection");}operations.put(operation.key,operation);}
 public void cancel(PendingIntent operation){operations.remove(operation.key);}
}
