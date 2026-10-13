package android.content;
import java.util.Map;
public interface SharedPreferences { Map<String,?> getAll(); String getString(String key,String fallback); Editor edit();
 interface Editor { Editor putString(String key,String value); Editor remove(String key); boolean commit(); }
}
