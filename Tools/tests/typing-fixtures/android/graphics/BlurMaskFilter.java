package android.graphics;
public class BlurMaskFilter {
    public enum Blur { NORMAL }
    public BlurMaskFilter(float radius, Blur blur) { if (radius<=0) throw new IllegalArgumentException(); }
}
