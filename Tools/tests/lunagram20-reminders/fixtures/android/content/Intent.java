package android.content;
import android.net.Uri;
import java.util.HashMap;
import java.util.Map;
public class Intent {
 public static final String ACTION_BOOT_COMPLETED="boot", ACTION_MY_PACKAGE_REPLACED="replaced";
 public static final int FLAG_ACTIVITY_CLEAR_TOP=0x04000000;
 public Class<?> target; private String action; public Uri data; public int flags; private final Map<String,Object> extras=new HashMap<>();
 public Intent(){} public Intent(Context context,Class<?> target){this.target=target;}
 public Intent setAction(String value){action=value;return this;} public String getAction(){return action;}
 public Intent setData(Uri value){data=value;return this;} public Intent setFlags(int value){flags=value;return this;}
 public Intent putExtra(String key,long value){extras.put(key,value);return this;}
 public Intent putExtra(String key,int value){extras.put(key,value);return this;}
 public Intent putExtra(String key,String value){extras.put(key,value);return this;}
 public boolean hasExtra(String key){return extras.containsKey(key);}
 public int getIntExtra(String key,int fallback){Object value=extras.get(key);return value instanceof Integer ? (int)value:fallback;}
 public long getLongExtra(String key,long fallback){Object value=extras.get(key);return value instanceof Long ? (long)value:fallback;}
 public String getStringExtra(String key){Object value=extras.get(key);return value instanceof String?(String)value:null;}
}
