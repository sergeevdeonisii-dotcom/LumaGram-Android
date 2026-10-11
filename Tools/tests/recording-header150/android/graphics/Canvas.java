package android.graphics;
public class Canvas {
    public float x, y, radius;
    public int alpha, color, calls;
    public void drawCircle(float x, float y, float radius, Paint paint) {
        this.x = x; this.y = y; this.radius = radius;
        alpha = paint.getAlpha(); color = paint.getColor(); calls++;
    }
}
