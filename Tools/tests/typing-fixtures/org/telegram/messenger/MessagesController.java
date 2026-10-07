package org.telegram.messenger;
import android.content.SharedPreferences;
import java.util.*;
public class MessagesController {
    public static final Map<String,Object> values=new HashMap<>();
    private static final SharedPreferences PREFS=new SharedPreferences() {
        public Map<String,?> getAll(){return values;}public boolean contains(String k){return values.containsKey(k);}
        public boolean getBoolean(String k,boolean v){return (boolean)values.getOrDefault(k,v);}
        public int getInt(String k,int v){return (int)values.getOrDefault(k,v);}
        public long getLong(String k,long v){return (long)values.getOrDefault(k,v);}
        public String getString(String k,String v){return (String)values.getOrDefault(k,v);}
        @SuppressWarnings("unchecked")public Set<String> getStringSet(String k,Set<String> v){return (Set<String>)values.getOrDefault(k,v);}
        public Editor edit(){return new Editor(){
            public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
            public Editor putInt(String k,int v){values.put(k,v);return this;}
            public Editor putLong(String k,long v){values.put(k,v);return this;}
            public Editor putString(String k,String v){values.put(k,v);return this;}
            public Editor putStringSet(String k,Set<String> v){values.put(k,v);return this;}
            public Editor remove(String k){values.remove(k);return this;}public Editor clear(){values.clear();return this;}
            public void apply(){}
        };}
    };
    public static SharedPreferences getGlobalMainSettings(){return PREFS;}
}
