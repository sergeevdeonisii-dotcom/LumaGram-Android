package org.telegram.messenger;
import java.util.ArrayDeque;
public final class Utilities {
    public static final GlobalQueue globalQueue=new GlobalQueue();
    public static final class GlobalQueue {
        private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public boolean postRunnable(Runnable task){tasks.add(task);return true;}
        public void drain(){while(!tasks.isEmpty())tasks.remove().run();}
        public void clear(){tasks.clear();}
        public int pending(){return tasks.size();}
    }
}
