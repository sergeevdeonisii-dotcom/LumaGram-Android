package android.opengl;
import java.util.ArrayList;
public final class EGLExt {
    public static final ArrayList<Long> times = new ArrayList<>();
    public static void eglPresentationTimeANDROID(EGLDisplay d, EGLSurface s, long time) { times.add(time); }
}
