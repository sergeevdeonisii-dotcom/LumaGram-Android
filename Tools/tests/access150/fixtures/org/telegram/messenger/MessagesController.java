package org.telegram.messenger;
import android.content.SharedPreferences;
import java.util.*;
public final class MessagesController {
    public static final int UPDATE_MASK_ALL=65535;
    public static final Map<Integer,Map<String,Object>> values=new HashMap<>();
    public static final boolean[] ghost=new boolean[4];
    public static final int[] ghostChanges=new int[4];
    public static int toastAtGhostDisable=-1;
    private final int account;
    private MessagesController(int account) { this.account=account; }
    public static MessagesController getInstance(int account) { return new MessagesController(account); }
    public void setLumaGhostModeEnabled(boolean enabled) { ghost[account]=enabled; ghostChanges[account]++; if(!enabled)toastAtGhostDisable=AccessUiTrace.toastShown; }
    public static SharedPreferences getMainSettings(int account) {
        final Map<String,Object> data=values.computeIfAbsent(account,k->new HashMap<>());
        return new SharedPreferences() {
            public Map<String,?> getAll(){return data;} public boolean contains(String k){return data.containsKey(k);}
            public boolean getBoolean(String k,boolean d){return (boolean)data.getOrDefault(k,d);}
            public int getInt(String k,int d){return (int)data.getOrDefault(k,d);}
            public long getLong(String k,long d){return (long)data.getOrDefault(k,d);}
            public float getFloat(String k,float d){return (float)data.getOrDefault(k,d);}
            public String getString(String k,String d){return (String)data.getOrDefault(k,d);}
            @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)data.getOrDefault(k,d);}
            public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
            public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
            public Editor edit(){return new Editor(){
                public Editor putBoolean(String k,boolean v){data.put(k,v);return this;} public Editor putInt(String k,int v){data.put(k,v);return this;}
                public Editor putLong(String k,long v){data.put(k,v);return this;} public Editor putFloat(String k,float v){data.put(k,v);return this;}
                public Editor putString(String k,String v){data.put(k,v);return this;} public Editor putStringSet(String k,Set<String>v){data.put(k,v);return this;}
                public Editor remove(String k){data.remove(k);return this;} public Editor clear(){data.clear();return this;}
                public void apply(){} public boolean commit(){return true;}
            };}
        };
    }
}
