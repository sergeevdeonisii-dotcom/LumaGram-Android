package android.app;

import android.view.Window;

public final class Activity {
    private final Window window;
    public Activity(Window window) { this.window = window; }
    public Window getWindow() { return window; }
}
