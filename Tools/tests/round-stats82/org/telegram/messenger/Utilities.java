package org.telegram.messenger;
import java.util.ArrayDeque;
public final class Utilities {
    public static final Queue globalQueue = new Queue();
    public static final class Queue {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void postRunnable(Runnable task) { tasks.addLast(task); }
        public void runFirst() { tasks.removeFirst().run(); }
        public void runLast() { tasks.removeLast().run(); }
        public void drain() { while (!tasks.isEmpty()) runFirst(); }
        public int size() { return tasks.size(); }
    }
}
