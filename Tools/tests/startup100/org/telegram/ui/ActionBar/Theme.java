package org.telegram.ui.ActionBar;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
/** Compile-only theme seam, not contrast or native appearance evidence. */
public final class Theme {
    public static final int key_dialogBackground=1, key_dialogTextBlack=2,
            key_featuredStickers_buttonText=3, key_featuredStickers_addButton=4;
    public static int getColor(int key) { return 0; }
    public static final class AdaptiveRipple {
        public static Drawable filledRect(int color, float... radii) { return new ColorDrawable(color); }
    }
}
