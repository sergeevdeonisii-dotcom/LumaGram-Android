package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG=1;
    private int alpha=255;
    private int color=0xFF000000;
    private Object filter;
    public Paint() {} public Paint(int flags) {}
    public void set(Paint other) { alpha=other.alpha; filter=other.filter; }
    public int getAlpha() { return alpha; }
    public void setAlpha(int value) { alpha=value; }
    public void setMaskFilter(Object value) { filter=value; }
    public void setColor(int value) { color=value;alpha=(value>>>24); }
    public int getColor() { return color; }
    public Object getMaskFilter() { return filter; }
}
