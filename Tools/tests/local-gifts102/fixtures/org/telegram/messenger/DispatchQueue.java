package org.telegram.messenger;
import java.util.ArrayList;
public final class DispatchQueue {
    private final ArrayList<Runnable> tasks = new ArrayList<>();
    public synchronized boolean postRunnable(Runnable task) { tasks.add(task); return true; }
    public synchronized void cancelRunnable(Runnable task) { tasks.remove(task); }
    public synchronized int size() { return tasks.size(); }
    public synchronized void clear() { tasks.clear(); }
    public void runNext() {
        Runnable task;
        synchronized(this) { task = tasks.remove(0); }
        task.run();
    }
}
