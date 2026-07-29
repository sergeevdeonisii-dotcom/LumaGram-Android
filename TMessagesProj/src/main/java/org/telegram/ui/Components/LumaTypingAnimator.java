package org.telegram.ui.Components;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.view.Gravity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LumaTextAnimation;

import java.util.ArrayList;
import java.util.Iterator;

final class LumaTypingAnimator implements TextWatcher {

    private static final long DURATION_MS = 300L;
    private static final long WORD_DURATION_MS = 340L;
    private static final float BLUR_TEXT_DELAY = 0.20f;
    private static final float BLUR_RADIUS_PX = 10.0f;
    private static final float SLIDE_DISTANCE_DP = 20.0f;
    private static final float WORD_SLIDE_DISTANCE_DP = 10.0f;
    private static final int MAX_GLYPHS_PER_CHANGE = 48;
    private static final int MAX_ACTIVE_GLYPHS = 72;
    private static final int MAX_INSERT_SCAN_UNITS = 256;
    private static final int MAX_CHANGE_COMPARE_UNITS = 512;
    private static final int BLUR_FILTER_STEPS = 8;

    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final ArrayList<Candidate> candidates = new ArrayList<>(MAX_GLYPHS_PER_CHANGE);
    private final BlurMaskFilter[] blurFilters = new BlurMaskFilter[BLUR_FILTER_STEPS];
    private final TextPaint animationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private EditTextBoldCursor view;
    private boolean watcherAttached;
    private boolean target;
    private int pendingStart;
    private int pendingBefore;
    private int pendingCount;
    private String pendingOldSegment;
    private boolean pendingSyntheticChange;

    void setTarget(EditTextBoldCursor view, boolean target) {
        if (this.view != null && this.view != view && watcherAttached) {
            this.view.removeTextChangedListener(this);
            watcherAttached = false;
        }
        this.view = view;
        this.target = target;
        if (target && !watcherAttached) {
            view.addTextChangedListener(this);
            watcherAttached = true;
        } else if (!target) {
            if (watcherAttached) {
                view.removeTextChangedListener(this);
                watcherAttached = false;
            }
            clear(view);
            this.view = null;
        }
    }

    void beforeDraw(EditTextBoldCursor view) {
        final Editable editable = view.getText();
        if (editable == null) {
            return;
        }
        if (!canAnimate(view)) {
            clearGlyphs(editable);
            return;
        }
        pruneFinishedGlyphs(editable, SystemClock.uptimeMillis());
    }

    void afterDraw(EditTextBoldCursor view, Canvas canvas) {
        if (!canAnimate(view) || glyphs.isEmpty()) {
            return;
        }
        final Layout layout = view.getLayout();
        final Editable editable = view.getText();
        if (layout == null || editable == null) {
            return;
        }

        final long now = SystemClock.uptimeMillis();
        final int paddingLeft = view.getPaddingLeft();
        final int scrollX = view.getScrollX();
        final float verticalOffset = getVerticalOffset(view);
        // View.draw() has already translated this canvas by -scrollY before
        // EditTextBoldCursor.onDraw(). Subtracting it here again makes letters
        // jump increasingly high as the multiline input starts scrolling.
        final float textTop = view.getExtendedPaddingTop() + verticalOffset;

        animationPaint.set(view.getPaint());
        final int originalAlpha = animationPaint.getAlpha();

        canvas.save();
        canvas.clipRect(0, 0, view.getWidth(), view.getHeight());
        for (int i = 0; i < glyphs.size(); i++) {
            final Glyph glyph = glyphs.get(i);
            final int start = editable.getSpanStart(glyph.hiddenSpan);
            final int end = editable.getSpanEnd(glyph.hiddenSpan);
            if (!isValidGlyph(editable, glyph, start, end) || now < glyph.startTime) {
                continue;
            }

            final long duration = glyph.wordBatch ? WORD_DURATION_MS : DURATION_MS;
            final float progress = Math.min(1.0f, (now - glyph.startTime) / (float) duration);
            final float eased = glyph.wordBatch ? easeOutCubic(progress) : easeOutQuint(progress);
            final int line = layout.getLineForOffset(start);
            final float x = paddingLeft + layout.getPrimaryHorizontal(start) - scrollX;
            final float baseline = textTop + layout.getLineBaseline(line)
                - AndroidUtilities.dp(glyph.wordBatch ? WORD_SLIDE_DISTANCE_DP : SLIDE_DISTANCE_DP)
                    * (1.0f - eased);
            if (baseline < -view.getTextSize() || baseline > view.getHeight() + view.getTextSize()) {
                continue;
            }

            final float blur = 1.0f - eased;
            final int blurAlpha = (int) (originalAlpha * blur);
            if (blurAlpha > 4) {
                animationPaint.setAlpha(blurAlpha);
                animationPaint.setMaskFilter(getBlurFilter(blur));
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
            clearGlyphs(editable);
        } else {
            glyphs.clear();
        }
        view.invalidate();
    }

    @Override
    public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        pendingStart = start;
        pendingBefore = count;
        pendingCount = after;

        final int textLength = text != null ? text.length() : 0;
        final int safeStart = Math.max(0, Math.min(textLength, start));
        final int safeEnd = Math.max(safeStart, Math.min(textLength, start + count));
        pendingSyntheticChange = start < 0 || count < 0
            || safeStart != start || safeEnd - safeStart != count;
        pendingOldSegment = !pendingSyntheticChange && count <= MAX_CHANGE_COMPARE_UNITS
            ? text.subSequence(safeStart, safeEnd).toString()
            : null;
    }

    @Override
    public void onTextChanged(CharSequence text, int start, int before, int count) {
        pendingSyntheticChange |= start != pendingStart || before != pendingBefore;
        pendingStart = start;
        pendingBefore = before;
        pendingCount = count;
    }

    @Override
    public void afterTextChanged(Editable editable) {
        final EditTextBoldCursor targetView = view;
        if (targetView == null || editable == null) {
            resetPendingChange();
            return;
        }
        final int start = pendingStart;
        final int count = pendingCount;
        final String oldSegment = pendingOldSegment;
        final boolean syntheticChange = pendingSyntheticChange;
        resetPendingChange();

        if (!canAnimate(targetView)) {
            clearGlyphs(editable);
            return;
        }
        if (syntheticChange) {
            pruneInvalidGlyphs(editable);
            return;
        }
        trackTextChange(editable, start, count, oldSegment, SystemClock.uptimeMillis());
        targetView.postInvalidateOnAnimation();
    }

    private boolean canAnimate(EditTextBoldCursor view) {
        return target && LumaTextAnimation.isEnabled()
            && !(view.getTransformationMethod() instanceof PasswordTransformationMethod);
    }

    private void resetPendingChange() {
        pendingStart = 0;
        pendingBefore = 0;
        pendingCount = 0;
        pendingOldSegment = null;
        pendingSyntheticChange = false;
    }

    private void trackTextChange(Editable editable, int start, int count,
                                 String oldSegment, long now) {
        pruneInvalidGlyphs(editable);
        if (count <= 0 || editable.length() == 0) {
            return;
        }

        final int insertedStart = Math.max(0, Math.min(editable.length(), start));
        final int insertedEnd = Math.max(insertedStart, Math.min(editable.length(), start + count));
        int changedStart = insertedStart;
        int changedEnd = insertedEnd;

        if (oldSegment != null) {
            final int oldLength = oldSegment.length();
            final int newLength = insertedEnd - insertedStart;
            final int commonLength = Math.min(oldLength, newLength);
            int prefix = 0;
            while (prefix < commonLength
                && oldSegment.charAt(prefix) == editable.charAt(insertedStart + prefix)) {
                prefix++;
            }

            int suffix = 0;
            while (suffix < oldLength - prefix && suffix < newLength - prefix
                && oldSegment.charAt(oldLength - 1 - suffix)
                    == editable.charAt(insertedEnd - 1 - suffix)) {
                suffix++;
            }
            changedStart += prefix;
            changedEnd -= suffix;
        }

        // A swipe keyboard normally commits the finished word as one pure
        // insertion. Draw that insertion as one shaped run so the letters
        // travel together. Do not regroup composing-word replacements: IMEs
        // update those repeatedly while the finger is moving, and restarting
        // the whole word on every replacement is what caused the visible jerk.
        int groupedStart = changedStart;
        int groupedEnd = changedEnd;
        while (groupedStart < groupedEnd && Character.isWhitespace(editable.charAt(groupedStart))) {
            groupedStart++;
        }
        while (groupedEnd > groupedStart && Character.isWhitespace(editable.charAt(groupedEnd - 1))) {
            groupedEnd--;
        }
        final boolean animateAsWord = oldSegment != null && oldSegment.isEmpty()
            && Character.codePointCount(editable, groupedStart, groupedEnd) > 1
            && containsOnlyWordCharacters(editable, groupedStart, groupedEnd);

        // Keep the previous letter animating when the IME replaces its whole
        // composing word to append the next character. Touching ranges are not
        // overlapping ranges.
        removeGlyphsOverlapping(editable, changedStart, changedEnd, false);
        if (changedEnd <= changedStart) {
            return;
        }

        int scanStart = Math.max(changedStart, changedEnd - MAX_INSERT_SCAN_UNITS);
        if (scanStart > changedStart && scanStart < editable.length()
            && Character.isLowSurrogate(editable.charAt(scanStart))
            && Character.isHighSurrogate(editable.charAt(scanStart - 1))) {
            scanStart--;
        }

        candidates.clear();
        if (animateAsWord) {
            candidates.add(new Candidate(
                groupedStart,
                groupedEnd,
                editable.subSequence(groupedStart, groupedEnd).toString(),
                true
            ));
        } else {
            int offset = scanStart;
            while (offset < changedEnd) {
                final int codePoint = Character.codePointAt(editable, offset);
                final int length = Character.charCount(codePoint);
                final int end = Math.min(changedEnd, offset + length);
                if (!isWhitespace(editable, offset, end)
                    && !isEmojiLike(editable, offset, end, codePoint)) {
                    if (candidates.size() == MAX_GLYPHS_PER_CHANGE) {
                        candidates.remove(0);
                    }
                    candidates.add(new Candidate(offset, end, editable.subSequence(offset, end).toString(), false));
                }
                offset = end;
            }
        }

        while (glyphs.size() + candidates.size() > MAX_ACTIVE_GLYPHS && !glyphs.isEmpty()) {
            removeGlyphAt(editable, 0);
        }
        for (int i = 0; i < candidates.size(); i++) {
            final Candidate candidate = candidates.get(i);
            if (candidate.start < 0 || candidate.end > editable.length() || candidate.end <= candidate.start) {
                continue;
            }
            final ForegroundColorSpan hiddenSpan = new ForegroundColorSpan(0x00000000);
            editable.setSpan(hiddenSpan, candidate.start, candidate.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            glyphs.add(new Glyph(candidate.text, hiddenSpan, now, candidate.wordBatch));
        }
    }

    private void pruneFinishedGlyphs(Editable editable, long now) {
        final Iterator<Glyph> iterator = glyphs.iterator();
        while (iterator.hasNext()) {
            final Glyph glyph = iterator.next();
            final int start = editable.getSpanStart(glyph.hiddenSpan);
            final int end = editable.getSpanEnd(glyph.hiddenSpan);
            final long duration = glyph.wordBatch ? WORD_DURATION_MS : DURATION_MS;
            if (!isValidGlyph(editable, glyph, start, end) || now - glyph.startTime >= duration) {
                editable.removeSpan(glyph.hiddenSpan);
                iterator.remove();
            }
        }
    }

    private void pruneInvalidGlyphs(Editable editable) {
        final Iterator<Glyph> iterator = glyphs.iterator();
        while (iterator.hasNext()) {
            final Glyph glyph = iterator.next();
            final int start = editable.getSpanStart(glyph.hiddenSpan);
            final int end = editable.getSpanEnd(glyph.hiddenSpan);
            if (!isValidGlyph(editable, glyph, start, end)) {
                editable.removeSpan(glyph.hiddenSpan);
                iterator.remove();
            }
        }
    }

    private void removeGlyphsOverlapping(Editable editable, int start, int end, boolean includeTouching) {
        final Iterator<Glyph> iterator = glyphs.iterator();
        while (iterator.hasNext()) {
            final Glyph glyph = iterator.next();
            final int glyphStart = editable.getSpanStart(glyph.hiddenSpan);
            final int glyphEnd = editable.getSpanEnd(glyph.hiddenSpan);
            final boolean overlaps = includeTouching
                ? glyphStart <= end && glyphEnd >= start
                : glyphStart < end && glyphEnd > start;
            if (overlaps) {
                editable.removeSpan(glyph.hiddenSpan);
                iterator.remove();
            }
        }
    }

    private void removeGlyphAt(Editable editable, int index) {
        final Glyph glyph = glyphs.remove(index);
        editable.removeSpan(glyph.hiddenSpan);
    }

    private void clearGlyphs(Editable editable) {
        for (int i = 0; i < glyphs.size(); i++) {
            editable.removeSpan(glyphs.get(i).hiddenSpan);
        }
        glyphs.clear();
        candidates.clear();
    }

    private BlurMaskFilter getBlurFilter(float blur) {
        final int index = Math.max(1, Math.min(BLUR_FILTER_STEPS - 1,
            Math.round(blur * (BLUR_FILTER_STEPS - 1))));
        if (blurFilters[index] == null) {
            blurFilters[index] = new BlurMaskFilter(
                Math.max(0.1f, BLUR_RADIUS_PX * index / (BLUR_FILTER_STEPS - 1.0f)),
                BlurMaskFilter.Blur.NORMAL
            );
        }
        return blurFilters[index];
    }

    private static boolean isValidGlyph(Editable editable, Glyph glyph, int start, int end) {
        if (start < 0 || end <= start || end > editable.length() || end - start != glyph.text.length()) {
            return false;
        }
        for (int i = 0; i < glyph.text.length(); i++) {
            if (editable.charAt(start + i) != glyph.text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsOnlyWordCharacters(CharSequence text, int start, int end) {
        int offset = start;
        while (offset < end) {
            final int codePoint = Character.codePointAt(text, offset);
            if (!isWordCharacter(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return offset > start;
    }

    private static boolean isWordCharacter(int codePoint) {
        final int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
            || type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK
            || type == Character.ENCLOSING_MARK
            || codePoint == '_'
            || codePoint == '\''
            || codePoint == 0x2019;
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

    private static float easeOutCubic(float value) {
        final float inverse = 1.0f - value;
        return 1.0f - inverse * inverse * inverse;
    }

    private static final class Candidate {
        final int start;
        final int end;
        final String text;
        final boolean wordBatch;

        Candidate(int start, int end, String text, boolean wordBatch) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.wordBatch = wordBatch;
        }
    }

    private static final class Glyph {
        final String text;
        final ForegroundColorSpan hiddenSpan;
        final long startTime;
        final boolean wordBatch;

        Glyph(String text, ForegroundColorSpan hiddenSpan, long startTime, boolean wordBatch) {
            this.text = text;
            this.hiddenSpan = hiddenSpan;
            this.startTime = startTime;
            this.wordBatch = wordBatch;
        }
    }
}
