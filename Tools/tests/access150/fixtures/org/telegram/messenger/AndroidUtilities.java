package org.telegram.messenger;
import java.util.ArrayList;
import android.os.SystemClock;
/** UI event queue: never automatically advances time or runs delayed network refresh. */
public final class AndroidUtilities {
    private static final class Task {
        final Runnable runnable; final long at;
        Task(Runnable r,long at) { runnable=r; this.at=at; }
    }
    private static final ArrayList<Task> tasks = new ArrayList<>();
    public static void runOnUIThread(Runnable r) { runOnUIThread(r,0); }
    public static void runOnUIThread(Runnable r,long delay) { tasks.add(new Task(r,SystemClock.elapsedRealtime()+delay)); }
    public static void cancelRunOnUIThread(Runnable r) { tasks.removeIf(t -> t.runnable==r); }
    public static void ready() {
        for(int budget=10000;budget>0;budget--) {
            int index=-1;
            for(int i=0;i<tasks.size();i++) if(tasks.get(i).at<=SystemClock.elapsedRealtime()) { index=i; break; }
            if(index<0) return;
            tasks.remove(index).runnable.run();
        }
        throw new AssertionError("Unbounded UI callbacks");
    }
    public static void clear() { tasks.clear(); }
    public static int pending() { return tasks.size(); }
}
