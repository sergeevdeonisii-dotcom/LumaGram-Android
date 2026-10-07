package org.telegram.messenger;
import java.io.File;
import java.util.*;
/** Controlled UI dispatch and clock; no Android Looper or wall-clock waiting. */
public class AndroidUtilities {
    private static final class Pending {final Runnable run;final long due;Pending(Runnable r,long d){run=r;due=d;}}
    private static final ArrayList<Pending> pending=new ArrayList<>();
    public static boolean deferImmediate;
    public static void runOnUIThread(Runnable run){if(deferImmediate)pending.add(new Pending(run,android.os.SystemClock.now));else run.run();}
    public static void runOnUIThread(Runnable run,long delay){pending.add(new Pending(run,android.os.SystemClock.now+delay));}
    public static void cancelRunOnUIThread(Runnable run){pending.removeIf(task->task.run==run);}
    public static void reset(){pending.clear();deferImmediate=false;android.os.SystemClock.now=1000;}
    public static void advanceBy(long delta){advanceTo(android.os.SystemClock.now+delta);}
    public static void drainReady(){advanceTo(android.os.SystemClock.now);}
    public static void advanceTo(long now){android.os.SystemClock.now=now;while(true){int selected=-1;long due=Long.MAX_VALUE;for(int i=0;i<pending.size();i++){Pending task=pending.get(i);if(task.due<=now&&task.due<due){due=task.due;selected=i;}}if(selected<0)return;pending.remove(selected).run.run();}}
    public static int pendingCount(){return pending.size();}
    public static boolean openForView(File f,String name,String mime,android.app.Activity a,Object o,boolean b){return false;}
}
