package android.os;
public final class HandlerThread extends Thread {
    public HandlerThread(String name) {}
    public void start() {}
    public Object getLooper() { return this; }
    public boolean quitSafely() { return true; }
}
