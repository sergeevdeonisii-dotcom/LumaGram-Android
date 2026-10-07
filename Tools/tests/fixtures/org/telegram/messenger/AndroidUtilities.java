package org.telegram.messenger;
import java.io.File;import java.util.*;
public class AndroidUtilities {
 private static final TreeMap<Long,ArrayList<Runnable>> tasks=new TreeMap<>();
 public static void runOnUIThread(Runnable r){r.run();}
 public static void runOnUIThread(Runnable r,long delay){tasks.computeIfAbsent(android.os.SystemClock.now+delay,k->new ArrayList<>()).add(r);}
 public static void cancelRunOnUIThread(Runnable r){for(ArrayList<Runnable> pending:tasks.values())pending.removeIf(task->task==r);}
 public static void advanceTo(long now){android.os.SystemClock.now=now;while(!tasks.isEmpty()&&tasks.firstKey()<=now){for(Runnable r:tasks.pollFirstEntry().getValue())r.run();}}
 public static boolean openForView(File f,String name,String mime,android.app.Activity a,Object o,boolean b){return false;}
}
