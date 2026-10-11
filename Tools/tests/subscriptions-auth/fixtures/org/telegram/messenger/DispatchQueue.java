package org.telegram.messenger;
import java.util.ArrayDeque;
public final class DispatchQueue {
    private static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public DispatchQueue(String name) {}
    public boolean postRunnable(Runnable task) { tasks.add(task); return true; }
    public static void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
}
