package org.telegram.ui.Components;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.view.Gravity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LumaTextAnimation;

import java.util.ArrayList;
import java.util.Iterator;

final class LumaTypingAnimator {

    private static final long DURATION_MS = 300L;
    private static final float BLUR_TEXT_DELAY = 0.20f;
    private static final float BLUR_RADIUS_PX = 10.0f;
    private static final float SLIDE_DISTANCE_DP = 20.0f;
    private static final int MAX_GLYPHS_PER_CHANGE = 32;

    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final ArrayList<ForegroundColorSpan> hiddenSpans = new ArrayList<>();
    private final TextPaint animationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private String previousText = "";
    private boolean initialized;
    private boolean target;

    void setTarget(EditTextBoldCursor view, boolean target) {
        this.target = target;
        if (!target) {
            clear(view);
        }
    }

    void beforeDraw(EditTextBoldCursor view) {
        final Editable editable = view.getText();
        if (editable == null) {
            return;
        }
        final String currentText = editable.toString();
        removeHiddenSpans(editable);

        if (!target || !LumaTextAnimation.isEnabled()
            || view.getTransformationMethod() instanceof PasswordTransformationMethod) {
            glyphs.clear();
            previousText = currentText;
            initialized = true;
            return;
        }

        final long now = SystemClock.uptimeMillis();
        pruneFinishedGlyphs(currentText, now);

        if (!initialized) {
            previousText = currentText;
            initialized = true;
        } else if (!currentText.equals(previousText)) {
            trackTextChange(editable, currentText, now);
            previousText = currentText;
        }

        for (int i = 0; i < glyphs.size(); i++) {
            final Glyph glyph = glyphs.get(i);
            if (glyph.index < 0 || glyph.index + glyph.length > editable.length()) {
                continue;
            }
            final ForegroundColorSpan span = new ForegroundColorSpan(0x00000000);
            editable.setSpan(span, glyph.index, glyph.index + glyph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            hiddenSpans.add(span);
        }
    }

    void afterDraw(EditTextBoldCursor view, Canvas canvas) {
        if (!target || !LumaTextAnimation.isEnabled() || glyphs.isEmpty()) {
            return;
        }
        final Layout layout = view.getLayout();
        final Editable editable = view.getText();
        if (layout == null || editable == null) {
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final int textLength = editable.length();
        final int paddingLeft = view.getPaddingLeft();
        final int scrollX = view.getScrollX();
        final float verticalOffset = getVerticalOffset(view);
        final float textTop = view.getExtendedPaddingTop() + verticalOffset;

        animationPaint.set(view.getPaint());
        final int originalAlpha = animationPaint.getAlpha();

        canvas.save();
        canvas.clipRect(0, 0, view.getWidth(), view.getHeight());
        for (int i = 0; i < glyphs.size(); i++) {
            final Glyph glyph = glyphs.get(i);
            if (glyph.index < 0 || glyph.index >= textLength || now < glyph.startTime) {
                continue;
            }
            final float progress = Math.min(1.0f, (now - glyph.startTime) / (float) DURATION_MS);
            final float eased = easeOutQuint(progress);
            final int line = layout.getLineForOffset(glyph.index);
            final float x = paddingLeft + layout.getPrimaryHorizontal(glyph.index) - scrollX;
            final float baseline = textTop + layout.getLineBaseline(line)
                - AndroidUtilities.dp(SLIDE_DISTANCE_DP) * (1.0f - eased);

            final float blur = 1.0f - eased;
            final int blurAlpha = (int) (originalAlpha * blur);
            if (blurAlpha > 4) {
                animationPaint.setAlpha(blurAlpha);
                animationPaint.setMaskFilter(new BlurMaskFilter(
                    Math.max(0.1f, BLUR_RADIUS_PX * blur),
                    BlurMaskFilter.Blur.NORMAL
                ));
                canvas.drawText(glyph.text, x, baseline, animationPaint);
            }

            final float sharp = eased > BLUR_TEXT_DELAY
                ? (eased - BLUR_TEXT_DELAY) / (1.0f - BLUR_TEXT_DELAY)
                : 0.0f;
            animationPaint.setMaskFilter(null);
            final int sharpAlpha = (int) (originalAlpha * sharp);
            if (sharpAlpha > 0) {
                animationPaint.setAlpha(sharpAlpha);
                canvas.drawText(glyph.text, x, baseline, animationPaint);
            }
        }
        canvas.restore();

        animationPaint.setMaskFilter(null);
        animationPaint.setAlpha(originalAlpha);
        if (!glyphs.isEmpty()) {
            view.postInvalidateOnAnimation();
        }
    }

    void clear(EditTextBoldCursor view) {
        final Editable editable = view.getText();
        if (editable != null) {
            removeHiddenSpans(editable);
            previousText = editable.toString();
        } else {
            previousText = "";
        }
        glyphs.clear();
        initialized = true;
        view.invalidate();
    }

    private void trackTextChange(Editable editable, String currentText, long now) {
        final int previousLength = previousText.length();
        final int currentLength = currentText.length();
        int prefix = 0;
        final int commonLength = Math.min(previousLength, currentLength);
        while (prefix < commonLength && previousText.charAt(prefix) == currentText.charAt(prefix)) {
            prefix++;
        }

        int suffix = 0;
        while (suffix < previousLength - prefix && suffix < currentLength - prefix
            && previousText.charAt(previousLength - 1 - suffix) == currentText.charAt(currentLength - 1 - suffix)) {
            suffix++;
        }

        final int oldChangedEnd = previousLength - suffix;
        final int newChangedEnd = currentLength - suffix;
        final int delta = (newChangedEnd - prefix) - (oldChangedEnd - prefix);

        final Iterator<Glyph> iterator = glyphs.iterator();
        while (iterator.hasNext()) {
            final Glyph glyph = iterator.next();
            if (glyph.index >= oldChangedEnd) {
                glyph.index += delta;
            } else if (glyph.index >= prefix) {
                iterator.remove();
            }
        }

        int animated = 0;
        int offset = prefix;
        while (offset < newChangedEnd && animated < MAX_GLYPHS_PER_CHANGE) {
            final int codePoint = currentText.codePointAt(offset);
            final int length = Character.charCount(codePoint);
            final int end = Math.min(currentText.length(), offset + length);
            final String text = currentText.substring(offset, end);
            if (!text.trim().isEmpty() && !isEmojiLike(editable, offset, end, codePoint)) {
                glyphs.add(new Glyph(offset, length, text, now));
                animated++;
            }
            offset = end;
        }
    }

    private void pruneFinishedGlyphs(String text, long now) {
        final Iterator<Glyph> iterator = glyphs.iterator();
        while (iterator.hasNext()) {
            final Glyph glyph = iterator.next();
            final boolean invalidRange = glyph.index < 0 || glyph.index + glyph.length > text.length();
            final boolean changedText = !invalidRange && !text.regionMatches(glyph.index, glyph.text, 0, glyph.length);
            if (invalidRange || changedText || now - glyph.startTime >= DURATION_MS) {
                iterator.remove();
            }
        }
    }

    private void removeHiddenSpans(Editable editable) {
        for (int i = 0; i < hiddenSpans.size(); i++) {
            editable.removeSpan(hiddenSpans.get(i));
        }
        hiddenSpans.clear();
    }

    private static boolean isEmojiLike(Editable editable, int start, int end, int codePoint) {
        if (Character.charCount(codePoint) > 1 || codePoint == 0x200D || codePoint == 0xFE0F || codePoint == 0x20E3) {
            return true;
        }
        if ((codePoint >= 0x2600 && codePoint <= 0x27BF) || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)) {
            return true;
        }
        try {
            final ReplacementSpan[] spans = editable.getSpans(start, end, ReplacementSpan.class);
            return spans != null && spans.length > 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static float getVerticalOffset(EditTextBoldCursor view) {
        if ((view.getGravity() & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP) {
            return 0.0f;
        }
        return view.getTotalPaddingTop() - view.getExtendedPaddingTop();
    }

    private static float easeOutQuint(float value) {
        final float inverse = 1.0f - value;
        return 1.0f - inverse * inverse * inverse * inverse * inverse;
    }

    private static final class Glyph {
        int index;
        final int length;
        final String text;
        final long startTime;

        Glyph(int index, int length, String text, long startTime) {
            this.index = index;
            this.length = length;
            this.text = text;
            this.startTime = startTime;
        }
    }
}
