package android.content;
import java.util.Map;
import java.util.Set;
public interface SharedPreferences {
 Map<String,?> getAll(); boolean contains(String key);
 boolean getBoolean(String key,boolean fallback); int getInt(String key,int fallback);
 long getLong(String key,long fallback); String getString(String key,String fallback);
 Set<String> getStringSet(String key,Set<String> fallback); Editor edit();
 interface Editor { Editor putBoolean(String k,boolean v); Editor putInt(String k,int v);
 Editor putLong(String k,long v); Editor putString(String k,String v); Editor putStringSet(String k,Set<String>v);
 Editor remove(String k); Editor clear(); void apply(); }
}
