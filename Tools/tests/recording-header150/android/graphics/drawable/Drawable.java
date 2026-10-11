package android.graphics.drawable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
public abstract class Drawable {
    private final Rect bounds = new Rect();
    private Callback callback;
    public interface Callback { void invalidateDrawable(Drawable drawable); }
    public Rect getBounds() { return bounds; }
    public void setBounds(int left, int top, int right, int bottom) {
        bounds.left = left; bounds.top = top; bounds.right = right; bounds.bottom = bottom;
    }
    public void setCallback(Callback callback) { this.callback = callback; }
    public Callback getCallback() { return callback; }
    public void invalidateSelf() { if (callback != null) callback.invalidateDrawable(this); }
    public abstract void draw(Canvas canvas);
    public abstract void setAlpha(int alpha);
    public abstract void setColorFilter(ColorFilter filter);
    public abstract int getOpacity();
    public int getIntrinsicWidth() { return -1; }
    public int getIntrinsicHeight() { return -1; }
}
