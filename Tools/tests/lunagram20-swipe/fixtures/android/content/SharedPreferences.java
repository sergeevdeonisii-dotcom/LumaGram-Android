package android.content;
import java.util.Map;
public interface SharedPreferences {
 Map<String,?> getAll(); boolean getBoolean(String key,boolean fallback); int getInt(String key,int fallback); String getString(String key,String fallback); Editor edit();
 interface Editor { Editor putBoolean(String key,boolean value); Editor putInt(String key,int value); Editor putString(String key,String value); Editor remove(String key); Editor clear(); void apply(); }
}
