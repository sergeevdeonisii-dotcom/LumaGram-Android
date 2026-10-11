package android.graphics;
public class Rect {
    public int left, top, right, bottom;
    public float exactCenterY() { return (top + bottom) * 0.5f; }
}
