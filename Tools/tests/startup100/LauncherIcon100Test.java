package org.telegram.ui;
import android.content.*;
import android.content.pm.PackageManager;
import java.util.*;
import org.telegram.messenger.ApplicationLoader;

public final class LauncherIcon100Test {
    private static int assertions;
    private static final class Prefs implements SharedPreferences, SharedPreferences.Editor {
        final Map<String,Object> values = new HashMap<>();
        public Map<String,?> getAll(){return values;}
        public boolean contains(String k){return values.containsKey(k);}
        public boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);}
        public int getInt(String k,int d){return (int)values.getOrDefault(k,d);}
        public long getLong(String k,long d){return (long)values.getOrDefault(k,d);}
        public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
        public Set<String> getStringSet(String k,Set<String>d){return d;}
        public Editor edit(){return this;}
        public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
        public Editor putInt(String k,int v){values.put(k,v);return this;}
        public Editor putLong(String k,long v){values.put(k,v);return this;}
        public Editor putString(String k,String v){values.put(k,v);return this;}
        public Editor putStringSet(String k,Set<String>v){values.put(k,v);return this;}
        public Editor remove(String k){values.remove(k);return this;}
        public Editor clear(){values.clear();return this;}
        public void apply(){}
    }
    private static final class FakeContext extends Context {
        final Prefs prefs=new Prefs(); final PackageManager pm=new PackageManager();
        public String getPackageName(){return "org.luma.liquid.web";}
        public PackageManager getPackageManager(){return pm;}
        public SharedPreferences getSharedPreferences(String name,int mode){
            if(!"systemConfig".equals(name)||mode!=MODE_PRIVATE)throw new AssertionError("private settings only");
            return prefs;
        }
    }
    private static FakeContext reset(){FakeContext ctx=new FakeContext();ApplicationLoader.applicationContext=ctx;return ctx;}
    private static void check(boolean valid){assertions++;if(!valid)throw new AssertionError("Icon assertion "+assertions);}
    private static void only(LauncherIconController.LauncherIcon expected){
        for(LauncherIconController.LauncherIcon icon:LauncherIconController.LauncherIcon.values())
            check(LauncherIconController.isEnabled(icon)==(icon==expected));
    }
    public static void main(String[]args){
        FakeContext ctx=reset();
        LauncherIconController.tryFixLauncherIconIfNeeded();
        only(LauncherIconController.LauncherIcon.DEFAULT);
        check(ctx.pm.writes.isEmpty());
        ctx=reset();
        LauncherIconController.setIcon(LauncherIconController.LauncherIcon.BLACK_HOLE);
        ctx.pm.writes.clear();
        LauncherIconController.tryFixLauncherIconIfNeeded();
        only(LauncherIconController.LauncherIcon.DEFAULT);
        check(ctx.pm.writes.get(0)==PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        LauncherIconController.setIcon(LauncherIconController.LauncherIcon.BLACK_HOLE);
        LauncherIconController.tryFixLauncherIconIfNeeded();
        only(LauncherIconController.LauncherIcon.BLACK_HOLE);
        for(LauncherIconController.LauncherIcon icon:LauncherIconController.LauncherIcon.values()){
            if(icon==LauncherIconController.LauncherIcon.BLACK_HOLE)continue;
            ctx=reset();LauncherIconController.setIcon(icon);ctx.pm.writes.clear();
            LauncherIconController.tryFixLauncherIconIfNeeded();only(icon);check(ctx.pm.writes.isEmpty());
        }
        ctx=reset();
        for(LauncherIconController.LauncherIcon icon:LauncherIconController.LauncherIcon.values())
            ctx.pm.states.put(icon.getComponentName(ctx),PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        LauncherIconController.tryFixLauncherIconIfNeeded();only(LauncherIconController.LauncherIcon.DEFAULT);
        ctx=reset();LauncherIconController.setIcon(LauncherIconController.LauncherIcon.BLACK_HOLE);
        ctx.pm.failNext=true;LauncherIconController.tryFixLauncherIconIfNeeded();
        only(LauncherIconController.LauncherIcon.BLACK_HOLE);
        LauncherIconController.tryFixLauncherIconIfNeeded();only(LauncherIconController.LauncherIcon.DEFAULT);
        System.out.println("Launcher migration: "+assertions+" assertions passed.");
    }
}
