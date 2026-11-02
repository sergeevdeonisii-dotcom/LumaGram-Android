package android.app;
import android.content.Context;
import android.content.Intent;
import java.util.HashMap;
import java.util.Map;
public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1, FLAG_IMMUTABLE=2;
 private static final Map<String,PendingIntent> registry=new HashMap<>();
 public Intent intent; public int flags; public final String key;
 private PendingIntent(String key){this.key=key;}
 private static PendingIntent get(String kind,int request,Intent intent,int flags){
  String key=kind+":"+request+":"+intent.target+":"+intent.getAction()+":"+intent.data;
  PendingIntent value=registry.computeIfAbsent(key,PendingIntent::new);value.intent=intent;value.flags=flags;return value;
 }
 public static PendingIntent getBroadcast(Context context,int request,Intent intent,int flags){return get("broadcast",request,intent,flags);}
 public static PendingIntent getActivity(Context context,int request,Intent intent,int flags){return get("activity",request,intent,flags);}
 public void cancel(){registry.remove(key);}
}
