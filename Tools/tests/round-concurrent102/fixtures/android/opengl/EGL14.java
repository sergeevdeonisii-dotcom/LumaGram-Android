package android.opengl;
import android.view.Surface;
import java.util.ArrayList;
public final class EGL14 {
    public static final EGLDisplay EGL_NO_DISPLAY = new EGLDisplay();
    public static final EGLContext EGL_NO_CONTEXT = new EGLContext();
    public static final EGLSurface EGL_NO_SURFACE = new EGLSurface();
    public static final int EGL_DEFAULT_DISPLAY = 0, EGL_ALPHA_SIZE = 1, EGL_BLUE_SIZE = 2,
        EGL_CONTEXT_CLIENT_VERSION = 3, EGL_GREEN_SIZE = 4, EGL_HEIGHT = 5, EGL_NONE = 6,
        EGL_OPENGL_ES2_BIT = 7, EGL_RED_SIZE = 8, EGL_RENDERABLE_TYPE = 9, EGL_WIDTH = 10;
    public static boolean current, failSwap;
    public static final ArrayList<EGLSurface> surfaces = new ArrayList<>();
    public static EGLDisplay eglGetDisplay(int display) { return new EGLDisplay(); }
    public static boolean eglInitialize(EGLDisplay d, int[] major, int mo, int[] minor, int mi) { return true; }
    public static boolean eglChooseConfig(EGLDisplay d, int[] a, int ao, EGLConfig[] out, int oo, int size, int[] count, int co) {
        out[oo] = new EGLConfig(); count[co] = 1; return true;
    }
    public static EGLContext eglCreateContext(EGLDisplay d, EGLConfig c, EGLContext parent, int[] a, int ao) { return new EGLContext(); }
    public static EGLSurface eglCreateWindowSurface(EGLDisplay d, EGLConfig c, Surface s, int[] a, int ao) {
        EGLSurface result = new EGLSurface(); surfaces.add(result); return result;
    }
    public static boolean eglMakeCurrent(EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext ctx) { current = ctx != EGL_NO_CONTEXT; return true; }
    public static int eglGetError() { return 0; }
    public static boolean eglQuerySurface(EGLDisplay d, EGLSurface s, int attr, int[] v, int vo) { v[vo] = 360; return true; }
    public static boolean eglSwapBuffers(EGLDisplay d, EGLSurface s) {
        if (failSwap) { failSwap = false; return false; } s.swaps++; return true;
    }
    public static boolean eglSwapInterval(EGLDisplay d, int interval) { return true; }
    public static boolean eglDestroyContext(EGLDisplay d, EGLContext ctx) { return true; }
    public static boolean eglDestroySurface(EGLDisplay d, EGLSurface s) { return true; }
    public static boolean eglReleaseThread() { return true; }
    public static boolean eglTerminate(EGLDisplay d) { return true; }
}
