package android.opengl;
import java.util.HashSet;
public final class GLES20 {
    public static final int GL_CLAMP_TO_EDGE = 1, GL_LINEAR = 2, GL_NEAREST = 3, GL_RENDERER = 4,
        GL_TEXTURE_MAG_FILTER = 5, GL_TEXTURE_MIN_FILTER = 6, GL_TEXTURE_WRAP_S = 7, GL_TEXTURE_WRAP_T = 8,
        GL_VENDOR = 9, GL_VERSION = 10;
    public static int next = 1, bound;
    public static final HashSet<Integer> deleted = new HashSet<>();
    private static void current() { if (!EGL14.current) throw new IllegalStateException("No current GL context"); }
    public static void glGenTextures(int count, int[] out, int offset) { current(); for (int i = 0; i < count; i++) out[offset + i] = next++; }
    public static void glBindTexture(int target, int id) { current(); bound = id; }
    public static void glDeleteTextures(int count, int[] ids, int offset) { current(); for (int i = 0; i < count; i++) deleted.add(ids[offset + i]); }
    public static void glTexParameteri(int target, int name, int value) { current(); }
    public static void glViewport(int x, int y, int w, int h) { current(); }
    public static void glFinish() { current(); }
    public static String glGetString(int name) { current(); return "simulated"; }
}
