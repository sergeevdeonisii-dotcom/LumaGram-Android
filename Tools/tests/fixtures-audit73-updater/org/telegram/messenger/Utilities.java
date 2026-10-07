package org.telegram.messenger;

import java.util.ArrayDeque;

/** Explicit background queue allows source changes while SHA verification is pending. */
public class Utilities {
    public static class Queue {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        public void postRunnable(Runnable runnable) { pending.add(runnable); }
        public void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }
    public static final Queue globalQueue = new Queue();
}
