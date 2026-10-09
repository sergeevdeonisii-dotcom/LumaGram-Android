package android.content;

public abstract class Context {
    public static final String SENSOR_SERVICE = "sensor";
    public static final String WINDOW_SERVICE = "window";
    public abstract Object getSystemService(String name);
}
