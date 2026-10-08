package org.telegram.messenger;
import java.util.ArrayList;
public final class AndroidUtilities {
    public static final ArrayList<Runnable> queue = new ArrayList<>();
    public static void runOnUIThread(Runnable runnable) { queue.add(runnable); }
    public static void flush() { while (!queue.isEmpty()) queue.remove(0).run(); }
}
