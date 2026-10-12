package org.telegram.messenger;
import java.util.ArrayDeque;
public final class DispatchQueue {
    private static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public DispatchQueue(String name) {}
    public boolean postRunnable(Runnable task) { tasks.add(task); return true; }
    public static void one() { if(tasks.isEmpty()) throw new AssertionError("No worker task"); tasks.remove().run(); }
    public static void last() { if(tasks.isEmpty()) throw new AssertionError("No worker task"); tasks.removeLast().run(); }
    public static void drain() { for(int n=1000;!tasks.isEmpty();n--) { if(n==0) throw new AssertionError("Unbounded worker queue"); one(); } }
    public static void clear() { tasks.clear(); }
    public static int pending() { return tasks.size(); }
}
