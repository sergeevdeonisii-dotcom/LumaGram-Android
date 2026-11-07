package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.style.ReplacementSpan;

import org.telegram.messenger.AndroidUtilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Compact rounded label used for the optional Lunagram plan badge in the dialogs header. */
public final class LunagramPlanBadgeSpan extends ReplacementSpan {
    private final int textColor;
    private final Typeface badgeTypeface;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public LunagramPlanBadgeSpan(int textColor) {
        this.textColor = textColor | 0xff000000;
        badgeTypeface = AndroidUtilities.bold();
        fillPaint.setColor(Color.WHITE);
        outlinePaint.setColor(0x33000000);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(dp(0.8f));
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
            @Nullable Paint.FontMetricsInt fm) {
        Paint badgePaint = new Paint(paint);
        badgePaint.setTypeface(badgeTypeface);
        return (int) Math.ceil(badgePaint.measureText(text, start, end) + dp(10));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
            int top, int baseline, int bottom, @NonNull Paint paint) {
        Paint badgePaint = new Paint(paint);
        badgePaint.setTypeface(badgeTypeface);
        final float textWidth = badgePaint.measureText(text, start, end);
        final Paint.FontMetrics metrics = badgePaint.getFontMetrics();
        final float left = x + dp(5);
        final float right = left + textWidth;
        final float topInset = Math.max(dp(1), (metrics.descent - metrics.ascent - dp(20)) / 2f);
        bounds.set(x, baseline + metrics.ascent + topInset, x + textWidth + dp(10), baseline + metrics.descent - topInset);
        final float radius = dp(5);
        canvas.drawRoundRect(bounds, radius, radius, fillPaint);
        canvas.drawRoundRect(bounds, radius, radius, outlinePaint);

        badgePaint.setColor(textColor);
        canvas.drawText(text, start, end, left, baseline, badgePaint);
    }
}
