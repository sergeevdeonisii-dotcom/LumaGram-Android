package android.view;

public final class Window {
    private final View decor;
    public Window(View decor) { this.decor = decor; }
    public View getDecorView() { return decor; }
}
