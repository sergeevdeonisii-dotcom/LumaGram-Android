package android.media;
import java.util.HashMap;
public final class MediaFormat {
    public static final String KEY_MIME = "mime", KEY_WIDTH = "width", KEY_HEIGHT = "height";
    public static final String KEY_FRAME_RATE = "frame-rate", KEY_BIT_RATE = "bitrate";
    private final HashMap<String, Object> values = new HashMap<>();
    public void setString(String key, String value) { values.put(key, value); }
    public void setInteger(String key, int value) { values.put(key, value); }
    public String getString(String key) { return (String) values.get(key); }
    public int getInteger(String key) { return (Integer) values.get(key); }
}
