package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramSwipeGesture;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Drawn over the message list; never changes bubble measurement or intercepts taps. */
public final class LunagramSwipeMenu {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Drawable[] icons = new Drawable[5];
    private final String[] labels = new String[5];
    private final StaticLayout[] layouts = new StaticLayout[5];
    private final Context context;
    private float left, top, menuWidth, menuHeight, rowHeight, scrollOffset;
    private int viewportWidth, viewportHeight;
    public LunagramSwipeMenu(Context context) {
        this.context = context;
        text.setTextSize(14 * context.getResources().getDisplayMetrics().scaledDensity);
        int[] resources = {0, R.drawable.menu_reply, R.drawable.msg_copy, R.drawable.msg_translate, R.drawable.msg_saved};
        int[] names = {0, R.string.Reply, R.string.Copy, R.string.TranslateMessage, R.string.LunagramSaveToSelf};
        for (int i = 1; i <= 4; i++) { icons[i] = context.getResources().getDrawable(resources[i]).mutate(); labels[i] = LocaleController.getString(names[i]); }
    }
    /** Measure real localized labels once per gesture, never in the move/draw loop. */
    public boolean prepare(LunagramSwipeGesture gesture, int width, int height, float insetTop, float insetBottom) {
        int[] actions = gesture.actions();
        float edge = AndroidUtilities.dp(12);
        float minY = Math.max(0, insetTop) + AndroidUtilities.dp(8);
        float maxY = height - Math.max(0, insetBottom) - AndroidUtilities.dp(8);
        if (actions.length == 0 || width <= edge * 2 + AndroidUtilities.dp(56) || maxY - minY < AndroidUtilities.dp(48)) return false;
        viewportWidth = width; viewportHeight = height;
        text.setTextSize(14 * context.getResources().getDisplayMetrics().scaledDensity);
        int[] names = {0, R.string.Reply, R.string.Copy, R.string.TranslateMessage, R.string.LunagramSaveToSelf};
        float labelWidth = 0;
        for (int action : actions) {
            labels[action] = LocaleController.getString(names[action]);
            labelWidth = Math.max(labelWidth, text.measureText(labels[action]));
        }
        menuWidth = Math.min(width - edge * 2, Math.max(AndroidUtilities.dp(172), (float) Math.ceil(labelWidth) + AndroidUtilities.dp(56)));
        int textWidth = Math.max(1, (int) menuWidth - AndroidUtilities.dp(56));
        rowHeight = AndroidUtilities.dp(48);
        for (int action : actions) {
            layouts[action] = new StaticLayout(labels[action], text, textWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            rowHeight = Math.max(rowHeight, layouts[action].getHeight() + AndroidUtilities.dp(16));
        }
        float fullHeight = rowHeight * actions.length;
        menuHeight = Math.min(fullHeight, maxY - minY);
        float below = maxY - gesture.originY(), above = gesture.originY() - minY;
        gesture.setDirection(below >= fullHeight - rowHeight / 2 || below >= above ? 1 : -1);
        float desiredTop = gesture.direction() > 0 ? gesture.originY() - rowHeight / 2
                : gesture.originY() + rowHeight / 2 - menuHeight;
        top = Math.max(minY, Math.min(maxY - menuHeight, desiredTop));
        left = width - menuWidth - edge;
        scrollOffset = gesture.direction() > 0 ? 0 : Math.max(0, fullHeight - menuHeight);
        return true;
    }
    public float rowHeight() { return rowHeight; }
    public void draw(Canvas canvas, LunagramSwipeGesture gesture, int width, int height, Theme.ResourcesProvider provider) {
        if (!gesture.menuActive() || gesture.selected() == LunagramSwipeGesture.NONE) return;
        if (width != viewportWidth || height != viewportHeight) { gesture.cancel(); return; }
        int[] actions = gesture.actions();
        int selectedVisual = gesture.direction() > 0 ? gesture.selectedIndex() : actions.length - 1 - gesture.selectedIndex();
        // Only a very short viewport scrolls the stable list; rows are never cyclically permuted.
        scrollOffset = Math.max(scrollOffset, (selectedVisual + 1) * rowHeight - menuHeight);
        scrollOffset = Math.min(scrollOffset, selectedVisual * rowHeight);
        bounds.set(left, top, left + menuWidth, top + menuHeight);
        background.setColor(Theme.getColor(Theme.key_dialogBackground, provider));
        background.setAlpha(255);
        canvas.drawRoundRect(bounds, AndroidUtilities.dp(14), AndroidUtilities.dp(14), background);
        int save = canvas.save();
        canvas.clipRect(left, top, left + menuWidth, top + menuHeight);
        for (int visual = 0; visual < actions.length; visual++) {
            int index = gesture.direction() > 0 ? visual : actions.length - 1 - visual;
            int action = actions[index]; float centerY = top + rowHeight * (visual + 0.5f) - scrollOffset;
            boolean selected = index == gesture.selectedIndex();
            int color = Theme.getColor(selected ? Theme.key_dialogTextBlue : Theme.key_dialogTextBlack, provider);
            if (selected) {
                highlight.setColor(Theme.getColor(Theme.key_dialogButtonSelector, provider));
                bounds.set(left + AndroidUtilities.dp(4), centerY - rowHeight / 2 + AndroidUtilities.dp(2), left + menuWidth - AndroidUtilities.dp(4), centerY + rowHeight / 2 - AndroidUtilities.dp(2));
                canvas.drawRoundRect(bounds, AndroidUtilities.dp(10), AndroidUtilities.dp(10), highlight);
            }
            Drawable icon = icons[action]; icon.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
            int iconLeft = (int) (left + AndroidUtilities.dp(12)); int iconTop = (int) (centerY - AndroidUtilities.dp(11));
            icon.setBounds(iconLeft, iconTop, iconLeft + AndroidUtilities.dp(22), iconTop + AndroidUtilities.dp(22)); icon.draw(canvas);
            text.setColor(color);
            int labelSave = canvas.save();
            canvas.translate(left + AndroidUtilities.dp(44), centerY - layouts[action].getHeight() / 2f);
            layouts[action].draw(canvas);
            canvas.restoreToCount(labelSave);
        }
        canvas.restoreToCount(save);
    }
}
