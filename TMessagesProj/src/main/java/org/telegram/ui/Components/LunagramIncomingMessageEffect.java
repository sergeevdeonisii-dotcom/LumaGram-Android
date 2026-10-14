package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.ReplacementSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LunagramSendEffectGeometry;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.IdentityHashMap;

/** Draw-only state owned by one RecyclerView add animation; never changes the message text. */
public final class LunagramIncomingMessageEffect {
    private final MessageObject message;
    private final int effect;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Path revealPath = new Path();
    private final IdentityHashMap<CharSequence, int[]> boundaries = new IdentityHashMap<>();
    private Drawable plane;
    private float progress;

    public LunagramIncomingMessageEffect(MessageObject message, int effect, Theme.ResourcesProvider resourcesProvider) {
        this.message = message;
        this.effect = effect;
        this.resourcesProvider = resourcesProvider;
    }

    public boolean matches(MessageObject value) { return value == message; }
    public void setProgress(float value) { progress = LunagramSendEffectGeometry.clamp(value); }

    /** Caller owns the Canvas save/restore, including the early plane-only frame. */
    public boolean beginDraw(Canvas canvas, ChatMessageCell cell, boolean drawPlane) {
        float centerX = (cell.getBackgroundDrawableLeft() + cell.getBackgroundDrawableRight()) / 2f;
        float top = cell.getBackgroundDrawableTop();
        if (effect == 2) {
            canvas.translate(0, AndroidUtilities.dpf2(LunagramSendEffectGeometry.dropY(progress)));
            float scale = LunagramSendEffectGeometry.dropScale(progress);
            canvas.scale(scale, scale, centerX, top);
            canvas.saveLayerAlpha(0, 0, cell.getWidth(), cell.getHeight(), (int) (255 * Math.min(1, progress / .16f)));
        } else if (effect == 3) {
            if (drawPlane) drawPlane(canvas, cell);
            float arrival = LunagramSendEffectGeometry.arrival(progress);
            if (arrival == 0) return false;
            float scale = .86f + .14f * CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(arrival);
            canvas.scale(scale, scale, centerX, top);
            canvas.saveLayerAlpha(0, 0, cell.getWidth(), cell.getHeight(), (int) (255 * Math.min(1, arrival * 3)));
        }
        return true;
    }

    private void drawPlane(Canvas canvas, ChatMessageCell cell) {
        if (progress >= .60f) return;
        if (plane == null) {
            plane = cell.getContext().getResources().getDrawable(R.drawable.msg_send).mutate();
            plane.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chat_messagePanelSend, resourcesProvider), PorterDuff.Mode.SRC_IN));
        }
        float t = Math.min(1, progress / .46f);
        float fromX = AndroidUtilities.dp(12);
        float fromY = AndroidUtilities.dp(12);
        float toX = cell.getBackgroundDrawableLeft() + AndroidUtilities.dp(24);
        float toY = cell.getBackgroundDrawableTop() + AndroidUtilities.dp(22);
        float controlX = Math.max(fromX, toX) + AndroidUtilities.dp(48);
        float controlY = Math.max(0, Math.min(fromY, toY) - AndroidUtilities.dp(24));
        float x = (1-t)*(1-t)*fromX + 2*(1-t)*t*controlX + t*t*toX;
        float y = (1-t)*(1-t)*fromY + 2*(1-t)*t*controlY + t*t*toY;
        int save = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(-18 * (float) Math.sin(t * Math.PI));
        int size = AndroidUtilities.dp(24);
        plane.setBounds(-size/2, -size/2, size/2, size/2);
        plane.setAlpha((int) (255 * Math.min(1, progress / .08f) * (1 - LunagramSendEffectGeometry.clamp((progress - .46f) / .14f))));
        plane.draw(canvas);
        canvas.restoreToCount(save);
    }

    /** Same grapheme/emoji-safe reveal as outgoing letters, in the layout's local coordinates. */
    public void clipText(Canvas canvas, StaticLayout layout) {
        if (effect != 1 || progress >= 1 || layout == null) return;
        CharSequence text = layout.getText();
        int[] stops = boundaries.get(text);
        if (stops == null) {
            stops = LunagramSendEffectGeometry.boundaries(text);
            boundaries.put(text, stops);
        }
        int end = LunagramSendEffectGeometry.revealEnd(stops, Math.min(1, progress / .90f));
        if (end > 0 && end < text.length() && text instanceof Spanned) {
            Spanned spanned = (Spanned) text;
            for (ReplacementSpan span : spanned.getSpans(end - 1, end, ReplacementSpan.class)) {
                end = Math.max(end, spanned.getSpanEnd(span));
            }
        }
        revealPath.reset();
        if (end > 0) layout.getSelectionPath(0, Math.min(text.length(), end), revealPath);
        canvas.clipPath(revealPath);
    }
}
