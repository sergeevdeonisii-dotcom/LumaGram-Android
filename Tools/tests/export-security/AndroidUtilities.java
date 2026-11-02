package org.telegram.messenger;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Explicit UI queue so account changes can occur between worker completion and delivery. */
public final class AndroidUtilities {
    private static final LinkedBlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
    public static void runOnUIThread(Runnable task) { ui.add(task); }
    public static void runOnUIThread(Runnable task, long delay) { ui.add(task); }
    public static void cancelRunOnUIThread(Runnable task) { ui.remove(task); }
    static void next() throws Exception {
        Runnable task = ui.poll(5, TimeUnit.SECONDS);
        if (task == null) throw new AssertionError("UI callback was not queued");
        task.run();
    }
    static void drain() { Runnable task; while ((task = ui.poll()) != null) task.run(); }
    public static boolean openForView(java.io.File file, String name, String mime,
            android.app.Activity activity, Object value, boolean flag) { return false; }
}
