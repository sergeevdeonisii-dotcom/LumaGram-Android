package android.util;
import java.util.ArrayList;
public final class Log {
    public static final ArrayList<String> warnings = new ArrayList<>();
    public static int w(String tag,String message) { warnings.add(tag+":"+message); return 0; }
}
