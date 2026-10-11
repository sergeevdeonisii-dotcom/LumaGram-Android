package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    private int alpha = 255, color;
    public Paint(int flags) { }
    public void setColor(int value) { color = value; }
    public int getColor() { return color; }
    public void setAlpha(int value) { alpha = value; }
    public int getAlpha() { return alpha; }
}
