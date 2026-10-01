package android.content;
import java.io.File;
import java.util.*;
import android.content.pm.PackageManager;
public class Context {
 public static final int MODE_PRIVATE=0;
 public File root;
 private final Map<String,SharedPreferences> stores=new HashMap<>();
 public SharedPreferences getSharedPreferences(String name,int mode) { return stores.computeIfAbsent(name,k->new MemoryPreferences()); }
 public File getCacheDir(){return new File(root,"private");}
 public File getExternalCacheDir(){return new File(root,"external");}
 public File getFilesDir(){return new File(root,"files");}
 public String getPackageName(){return "org.luma.liquid.web";}
 public PackageManager getPackageManager(){return new PackageManager();}
 public static class MemoryPreferences implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();
  public Map<String,?> getAll(){return new HashMap<>(values);}
  public boolean contains(String k){return values.containsKey(k);}
  public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
  public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)values.getOrDefault(k,d);}
  public Editor edit(){return new Editor(){
   final Map<String,Object> changes=new HashMap<>(); boolean clear;
   public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
   public Editor putInt(String k,int v){changes.put(k,v);return this;}
   public Editor putLong(String k,long v){changes.put(k,v);return this;}
   public Editor putString(String k,String v){changes.put(k,v);return this;}
   public Editor putStringSet(String k,Set<String>v){changes.put(k,new HashSet<>(v));return this;}
   public Editor remove(String k){changes.put(k,null);return this;}
   public Editor clear(){clear=true;return this;}
   public void apply(){if(clear)values.clear();changes.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});}
  };}
 }
}
