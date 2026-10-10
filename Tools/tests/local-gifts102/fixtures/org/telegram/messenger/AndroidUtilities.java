package org.telegram.messenger;
public final class AndroidUtilities {
    public static final DispatchQueue uiQueue = new DispatchQueue();
    public static void runOnUIThread(Runnable task) { uiQueue.postRunnable(task); }
}
