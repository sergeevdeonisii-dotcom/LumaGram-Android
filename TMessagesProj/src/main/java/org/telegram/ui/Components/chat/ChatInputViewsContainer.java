package org.telegram.ui.Components.chat;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.LiteMode;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.inset.InAppKeyboardInsetView;
import org.telegram.ui.Components.inset.WindowInsetsProvider;

public class ChatInputViewsContainer extends FrameLayout {
    public static final int INPUT_BUBBLE_RADIUS = 22;
    public static final int INPUT_KEYBOARD_RADIUS = 29;

    public static final int INPUT_BUBBLE_BOTTOM = 9;

    private WindowInsetsProvider windowInsetsProvider;

    private final View fadeView;
    private final FrameLayout inputIslandBubbleContainer;
    private final FrameLayout inAppKeyboardBubbleContainer;

    public ChatInputViewsContainer(@NonNull Context context) {
        super(context);

        inputIslandBubbleContainer = new FrameLayout(context);
        addView(inputIslandBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        inAppKeyboardBubbleContainer = new FrameLayout(context) {
            @Override
            public void addView(View child, int width, int height) {
                super.addView(child, width, height);
                checkViewsPositions();
            }
        };
        addView(inAppKeyboardBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fadeView = new View(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (backgroundWithFadeDrawable != null) {
                    backgroundWithFadeDrawable.draw(canvas);
                }
                super.dispatchDraw(canvas);
            }
        };
    }

    public View getFadeView() {
        return fadeView;
    }

    public void setWindowInsetsProvider(WindowInsetsProvider windowInsetsProvider) {
        this.windowInsetsProvider = windowInsetsProvider;
    }



    public boolean drawInputBackground = true;
    public BlurredBackgroundDrawable blurredBackgroundDrawable;
    private BlurredBackgroundDrawable leftButtonBackgroundDrawable;
    private BlurredBackgroundDrawable rightButtonBackgroundDrawable;
    private BlurredBackgroundDrawable underKeyboardBackgroundDrawable;
    private boolean splitInputEnabled = true;
    private int splitInputCenterInsetLeft = dp(52);
    private int splitInputCenterInsetRight = dp(52);
    private boolean drawSplitInputLeftButton = true;
    private boolean drawSplitInputRightButton = true;
    public void setInputIslandBubbleDrawable(BlurredBackgroundDrawable drawable) {
        blurredBackgroundDrawable = drawable;
        blurredBackgroundDrawable.setPadding(dp(7));
        blurredBackgroundDrawable.setRadius(dp(INPUT_BUBBLE_RADIUS));
        blurredBackgroundDrawable.setThickness(dp(11));
        blurredBackgroundDrawable.setIntensity(0.88f);
    }

    public void setInputIslandButtonDrawables(BlurredBackgroundDrawable left, BlurredBackgroundDrawable right) {
        leftButtonBackgroundDrawable = left;
        rightButtonBackgroundDrawable = right;
        leftButtonBackgroundDrawable.setPadding(dp(7));
        rightButtonBackgroundDrawable.setPadding(dp(7));
        leftButtonBackgroundDrawable.setRadius(dp(INPUT_BUBBLE_RADIUS));
        rightButtonBackgroundDrawable.setRadius(dp(INPUT_BUBBLE_RADIUS));
        leftButtonBackgroundDrawable.setThickness(dp(11));
        rightButtonBackgroundDrawable.setThickness(dp(11));
        leftButtonBackgroundDrawable.setIntensity(0.88f);
        rightButtonBackgroundDrawable.setIntensity(0.88f);
    }

    public void setSplitInputEnabled(boolean enabled) {
        if (splitInputEnabled != enabled) {
            splitInputEnabled = enabled;
            invalidate();
        }
    }

    public void setSplitInputLayout(int centerInsetLeft, int centerInsetRight,
                                    boolean drawLeftButton, boolean drawRightButton) {
        centerInsetLeft = Math.max(dp(8), centerInsetLeft);
        centerInsetRight = Math.max(dp(8), centerInsetRight);
        if (splitInputCenterInsetLeft == centerInsetLeft &&
            splitInputCenterInsetRight == centerInsetRight &&
            drawSplitInputLeftButton == drawLeftButton &&
            drawSplitInputRightButton == drawRightButton) {
            return;
        }
        splitInputCenterInsetLeft = centerInsetLeft;
        splitInputCenterInsetRight = centerInsetRight;
        drawSplitInputLeftButton = drawLeftButton;
        drawSplitInputRightButton = drawRightButton;
        invalidate();
    }

    public void setUnderKeyboardBackgroundDrawable(BlurredBackgroundDrawable drawable) {
        underKeyboardBackgroundDrawable = drawable;
        underKeyboardBackgroundDrawable.enableInAppKeyboardOptimization();
        underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), 0, 0);
        underKeyboardBackgroundDrawable.setThickness(dp(32));
        underKeyboardBackgroundDrawable.setIntensity(0.4f);
    }

    public void updateColors() {
        blurredBackgroundDrawable.updateColors();
        if (leftButtonBackgroundDrawable != null) leftButtonBackgroundDrawable.updateColors();
        if (rightButtonBackgroundDrawable != null) rightButtonBackgroundDrawable.updateColors();
        underKeyboardBackgroundDrawable.updateColors();
        invalidate();
    }


    @NonNull
    public FrameLayout getInputIslandBubbleContainer() {
        return inputIslandBubbleContainer;
    }

    @NonNull
    public FrameLayout getInAppKeyboardBubbleContainer() {
        return inAppKeyboardBubbleContainer;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        checkViewsPositions();
        checkInAppKeyboardChild();
    }



    private void checkInAppKeyboardViewHeight() {
        LayoutParams lp = (LayoutParams) inAppKeyboardBubbleContainer.getLayoutParams();

        final int oldHeight = lp.height;
        final int newHeight = windowInsetsProvider.getInAppKeyboardRecommendedViewHeight();

        if (oldHeight != newHeight) {
            lp.height = newHeight;
            requestLayout();
        }
    }

    private final Path underKeyboardPath = new Path();

    private int currentBlurredHeight;
    private void checkBlurredHeight(boolean force) {
        checkViewsPositions();

        final int blurredHeight = inputBubbleHeightRound + dp(INPUT_BUBBLE_BOTTOM) + Math.round(maxBottomInset);
        if (currentBlurredHeight != blurredHeight || force) {
            currentBlurredHeight = blurredHeight;

            final int r = dp(INPUT_KEYBOARD_RADIUS);
            tmpRectF.set(0, getMeasuredHeight() - imeBottomInset, getMeasuredWidth(), getMeasuredHeight());
            underKeyboardPath.rewind();
            underKeyboardPath.addRoundRect(tmpRectF, new float[] {r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            underKeyboardPath.close();
            invalidate();
        }
    }

    private float maxBottomInset;
    private float imeBottomInset;
    private boolean needDrawInAppKeyboard;

    public void checkInsets() {
        maxBottomInset = windowInsetsProvider.getAnimatedMaxBottomInset();
        imeBottomInset = windowInsetsProvider.getAnimatedImeBottomInset();

        needDrawInAppKeyboard = windowInsetsProvider.inAppViewIsVisible();

        if ((inAppKeyboardBubbleContainer.getVisibility() == VISIBLE) != needDrawInAppKeyboard) {
            inAppKeyboardBubbleContainer.setVisibility(needDrawInAppKeyboard ? VISIBLE : GONE);
        }

        checkInAppKeyboardViewHeight();
        checkBlurredHeight(false);
        checkInAppKeyboardChild();

        if (underKeyboardBackgroundDrawable != null) {
            int leftBottomRadius = 0;
            int rightBottomRadius = 0;
            if (Build.VERSION.SDK_INT >= 31) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    final RoundedCorner bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                    final RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                    leftBottomRadius = bottomLeft == null ? 0 : bottomLeft.getRadius();
                    rightBottomRadius = bottomRight == null ? 0 : bottomRight.getRadius();
                }
            }
            underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), rightBottomRadius, leftBottomRadius, true);
        }
    }

    private void checkViewsPositions() {
        inputIslandBubbleContainer.setTranslationY(-maxBottomInset - dp(INPUT_BUBBLE_BOTTOM));
        inAppKeyboardBubbleContainer.setTranslationY(inAppKeyboardBubbleContainer.getMeasuredHeight() - imeBottomInset);
    }


    private void checkInAppKeyboardChild() {
        final int navbarHeight = windowInsetsProvider.getCurrentNavigationBarInset();
        final float keyboardHeight = windowInsetsProvider.getAnimatedImeBottomInset();

        for (int a = 0, N = inAppKeyboardBubbleContainer.getChildCount(); a < N; a++) {
            final View child = inAppKeyboardBubbleContainer.getChildAt(a);
            if (child instanceof InAppKeyboardInsetView) {
                InAppKeyboardInsetView insetView = (InAppKeyboardInsetView) child;
                insetView.applyNavigationBarHeight(navbarHeight);
                insetView.applyInAppKeyboardAnimatedHeight(keyboardHeight);
            }
        }
    }



    /* */

    private float inputBubbleOffsetLeft;
    private float inputBubbleOffsetRight;

    private float inputBubbleHeight;
    private int inputBubbleHeightRound;
    public void setInputBubbleHeight(float height) {
        inputBubbleHeight = height;
        inputBubbleHeightRound = Math.round(inputBubbleHeight);
        checkBlurredHeight(false);
    }

    public void setInputBubbleOffsets(float left, float right) {
        inputBubbleOffsetLeft = left;
        inputBubbleOffsetRight = right;
        invalidate();
    }

    public float getInputBubbleHeight() {
        return inputBubbleHeight;
    }

    public float getInputBubbleTop() {
        return getInputBubbleBottom() - getInputBubbleHeight();
    }

    public float getInputBubbleBottom() {
        return getMeasuredHeight() - maxBottomInset - dp(INPUT_BUBBLE_BOTTOM);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkBlurredHeight(true);
        checkDrawableBounds();
        checkViewsPositions();
        checkInAppKeyboardChild();
    }

    /* Render */

    private final Rect tmpRect = new Rect();
    private final RectF tmpRectF = new RectF();
    private int lastLiquidInputHeight = -1;

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        underKeyboardBackgroundDrawable.setBounds(
            0,
            getMeasuredHeight() - (int) imeBottomInset,
            getMeasuredWidth(),
            Math.max(getMeasuredHeight(), getMeasuredHeight() - (int) imeBottomInset + dp(INPUT_KEYBOARD_RADIUS * 2))
        );

        final int blurTop = getMeasuredHeight() - currentBlurredHeight;

        final boolean splitInput = splitInputEnabled && leftButtonBackgroundDrawable != null && rightButtonBackgroundDrawable != null
            && inputBubbleOffsetLeft < 1 && inputBubbleOffsetRight < 1;

        if (splitInput) {
            final int sideOuter = dp(58);
            final int originalSingleLineHeight = Math.min(inputBubbleHeightRound, dp(44));
            final int configuredHeight = Math.min(originalSingleLineHeight, dp(LiteMode.getLiquidGlassInputHeightDp()));
            final int compactInset = Math.max(0, (originalSingleLineHeight - configuredHeight) / 2);
            final int centerTop = compactInset;
            final int centerBottom = Math.max(centerTop + 1, inputBubbleHeightRound - compactInset);
            final int sideHeight = Math.min(centerBottom - centerTop, configuredHeight);
            final int sideTop = centerBottom - sideHeight;
            final int yOffset = blurTop + (int) bubbleInputTranlationY;

            if (lastLiquidInputHeight != configuredHeight) {
                lastLiquidInputHeight = configuredHeight;
                final float radius = configuredHeight / 2f;
                blurredBackgroundDrawable.setRadius(radius);
                leftButtonBackgroundDrawable.setRadius(radius);
                rightButtonBackgroundDrawable.setRadius(radius);
            }

            final int centerRight = getMeasuredWidth() - splitInputCenterInsetRight;
            final int centerLeft = Math.min(splitInputCenterInsetLeft, centerRight - dp(24));
            tmpRect.set(centerLeft, centerTop, centerRight, centerBottom);
            tmpRect.inset(0, -dp(7));
            tmpRect.offset(0, yOffset);
            blurredBackgroundDrawable.setBounds(tmpRect);

            tmpRect.set(compactInset, sideTop, sideOuter - compactInset, centerBottom);
            tmpRect.inset(0, -dp(7));
            tmpRect.offset(0, yOffset);
            leftButtonBackgroundDrawable.setBounds(tmpRect);

            tmpRect.set(getMeasuredWidth() - sideOuter + compactInset, sideTop, getMeasuredWidth() - compactInset, centerBottom);
            tmpRect.inset(0, -dp(7));
            tmpRect.offset(0, yOffset);
            rightButtonBackgroundDrawable.setBounds(tmpRect);

            if (drawInputBackground) {
                blurredBackgroundDrawable.draw(canvas);
                if (drawSplitInputLeftButton) {
                    leftButtonBackgroundDrawable.draw(canvas);
                }
                if (drawSplitInputRightButton) {
                    rightButtonBackgroundDrawable.draw(canvas);
                }
            }
        } else {
            tmpRect.set(
                Math.round(inputBubbleOffsetLeft),
                0,
                getMeasuredWidth() - Math.round(inputBubbleOffsetRight),
                inputBubbleHeightRound
            );
            tmpRect.inset(0, -dp(7));
            tmpRect.offset(0, blurTop + (int) bubbleInputTranlationY);

            blurredBackgroundDrawable.setBounds(tmpRect);
            if (drawInputBackground)
                blurredBackgroundDrawable.draw(canvas);
        }

        if (needDrawInAppKeyboard) {
            underKeyboardBackgroundDrawable.draw(canvas);
        }

        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        final boolean needClip = child == inAppKeyboardBubbleContainer;
        if (needClip) {
            canvas.save();
            canvas.clipPath(underKeyboardBackgroundDrawable.getPath());
        }

        final boolean result = super.drawChild(canvas, child, drawingTime);
        if (needClip) {
            canvas.restore();
        }

        return result;
    }





    private BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable;

    public void setBackgroundWithFadeDrawable(BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable) {
        this.backgroundWithFadeDrawable = backgroundWithFadeDrawable;
    }

    private float blurredBottomHeight;
    public void setBlurredBottomHeight(float height) {
        if (blurredBottomHeight != height) {
            blurredBottomHeight = height;
            checkDrawableBounds();
        }
    }

    private float bubbleInputTranlationY;
    public void setInputBubbleTranslationY(float translationY) {
        this.bubbleInputTranlationY = translationY;
        invalidate();
    }

    public void setInputBubbleAlpha(int alpha) {
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.setAlpha(alpha);
        }
        if (leftButtonBackgroundDrawable != null) leftButtonBackgroundDrawable.setAlpha(alpha);
        if (rightButtonBackgroundDrawable != null) rightButtonBackgroundDrawable.setAlpha(alpha);

    }

    private void checkDrawableBounds() {
        if (backgroundWithFadeDrawable == null) {
            return;
        }

        final int oldBound = backgroundWithFadeDrawable.getBounds().top;
        final int newBound = getMeasuredHeight() - Math.round(blurredBottomHeight);

        if (oldBound != newBound) {
            backgroundWithFadeDrawable.setBounds(0, newBound, getMeasuredWidth(), getMeasuredHeight());
            fadeView.invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
            invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
        }
    }


    private boolean captured;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();

            captured = blurredBackgroundDrawable != null && blurredBackgroundDrawable.getAlpha() == 255 && blurredBackgroundDrawable.getBounds().contains(x, y)
                || drawSplitInputLeftButton && leftButtonBackgroundDrawable != null && leftButtonBackgroundDrawable.getAlpha() == 255 && leftButtonBackgroundDrawable.getBounds().contains(x, y)
                || drawSplitInputRightButton && rightButtonBackgroundDrawable != null && rightButtonBackgroundDrawable.getAlpha() == 255 && rightButtonBackgroundDrawable.getBounds().contains(x, y)
                || underKeyboardBackgroundDrawable != null && underKeyboardBackgroundDrawable.getBounds().contains(x, y);

        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            captured = false;
        }

        return captured;
    }
}
