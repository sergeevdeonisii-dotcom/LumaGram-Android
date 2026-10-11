package org.telegram.messenger.utils;
import android.graphics.drawable.Drawable;
public final class Choreographer60FpsContent {
    private static final Choreographer60FpsContent INSTANCE = new Choreographer60FpsContent();
    public static Choreographer60FpsContent getInstance() { return INSTANCE; }
    public void postInvalidateDrawable30fps(Drawable drawable) { drawable.invalidateSelf(); }
}
