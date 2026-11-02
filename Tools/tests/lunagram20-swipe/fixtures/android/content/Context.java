package android.content;
import java.util.HashMap;
import java.util.Map;
public class Context {
 public final android.content.res.Resources resources=new android.content.res.Resources();
 public android.content.res.Resources getResources(){return resources;}
 public static final int MODE_PRIVATE=0;private final Map<String,SharedPreferences> stores=new HashMap<>();
 public SharedPreferences getSharedPreferences(String key,int mode){return stores.computeIfAbsent(key,k->new Memory());}
 static class Memory implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();public Map<String,?> getAll(){return new HashMap<>(values);}
  public boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
  public int getInt(String key,int fallback){return (Integer)values.getOrDefault(key,fallback);}
  public String getString(String key,String fallback){return (String)values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){final Map<String,Object> changes=new HashMap<>();boolean clear;
   public Editor putBoolean(String key,boolean value){changes.put(key,value);return this;}public Editor putInt(String key,int value){changes.put(key,value);return this;}
   public Editor putString(String key,String value){changes.put(key,value);return this;}public Editor remove(String key){changes.put(key,null);return this;}
   public Editor clear(){clear=true;return this;}public void apply(){if(clear)values.clear();changes.forEach((key,value)->{if(value==null)values.remove(key);else values.put(key,value);});}
  };}
 }
}
