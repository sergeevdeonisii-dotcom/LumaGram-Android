package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class LiquidGlassSettingsActivity extends BaseFragment {

    private static final int ROW_ENABLED = 1;
    private static final int ROW_POWER_SAVER = 2;
    private static final int ROW_RESET = 3;

    private UniversalRecyclerView listView;
    private LiquidGlassPreviewView previewView;
    private boolean settingsChanged;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.LiquidGlassSettingsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));

        previewView = new LiquidGlassPreviewView(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS);
        items.add(UItem.asHeader(getString(R.string.LiquidGlassLivePreview)));
        items.add(UItem.asCustom(previewView, 226));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassAppearance)));
        items.add(UItem.asCheck(ROW_ENABLED, getString(R.string.LiquidGlassEnable))
            .setChecked(enabled));
        items.add(UItem.asCheck(ROW_POWER_SAVER, getString(R.string.LiquidGlassPowerSaver))
            .setChecked(LiteMode.getLiquidGlassKeepInPowerSaver()));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassEnableInfo)));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassPanelOpacity)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassTransparent),
            getString(R.string.LiquidGlassBalanced),
            getString(R.string.LiquidGlassDense)
        }, LiteMode.getLiquidGlassOpacityLevel(), value -> {
            LiteMode.setLiquidGlassOpacityLevel(value);
            settingsChanged = true;
            updatePreview();
        }).setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassRefraction)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassSoft),
            getString(R.string.LiquidGlassBalanced),
            getString(R.string.LiquidGlassStrong)
        }, LiteMode.getLiquidGlassIntensityLevel(), value -> {
            LiteMode.setLiquidGlassIntensityLevel(value);
            settingsChanged = true;
            updatePreview();
        }).setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassInputSize)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassThin),
            getString(R.string.LiquidGlassMedium),
            getString(R.string.LiquidGlassLarge)
        }, LiteMode.getLiquidGlassInputSizeLevel(), value -> {
            LiteMode.setLiquidGlassInputSizeLevel(value);
            settingsChanged = true;
            updatePreview();
        }).setEnabled(enabled));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassAdvancedInfo)));
        items.add(UItem.asButton(ROW_RESET, getString(R.string.LiquidGlassReset)));
        items.add(UItem.asShadow(null));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_ENABLED) {
            final boolean enabled = !LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS);
            LiteMode.toggleFlag(LiteMode.FLAG_LIQUID_GLASS, enabled);
            settingsChanged = true;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            updatePreview();
            listView.adapter.update(false);
        } else if (item.id == ROW_POWER_SAVER) {
            final boolean enabled = !LiteMode.getLiquidGlassKeepInPowerSaver();
            LiteMode.setLiquidGlassKeepInPowerSaver(enabled);
            settingsChanged = true;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            updatePreview();
        } else if (item.id == ROW_RESET) {
            LiteMode.resetLiquidGlassSettings();
            settingsChanged = true;
            updatePreview();
            listView.adapter.update(false);
        }
    }

    private void updatePreview() {
        if (previewView != null) {
            previewView.invalidate();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (settingsChanged && getParentActivity() instanceof LaunchActivity) {
            final LaunchActivity activity = (LaunchActivity) getParentActivity();
            AndroidUtilities.runOnUIThread(() -> {
                if (!activity.isFinishing()) {
                    activity.rebuildAllFragments(true);
                }
            }, 120);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePreview();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    private static class LiquidGlassPreviewView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        LiquidGlassPreviewView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float w = getWidth();
            final float h = getHeight();
            if (w <= 0 || h <= 0) return;

            final float outer = AndroidUtilities.dp(12);
            rect.set(outer, AndroidUtilities.dp(4), w - outer, h - AndroidUtilities.dp(4));
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[] {0xff10172a, 0xff27163d, 0xff0b1b2c}, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(22), AndroidUtilities.dp(22), paint);
            paint.setShader(null);

            drawGlow(canvas, w * .78f, AndroidUtilities.dp(42), AndroidUtilities.dp(92), 0x995f39ee);
            drawGlow(canvas, w * .22f, h * .72f, AndroidUtilities.dp(88), 0x774dc9ff);
            drawGlow(canvas, w * .62f, h * .63f, AndroidUtilities.dp(70), 0x66ee4aa8);

            final float top = AndroidUtilities.dp(17);
            final float button = AndroidUtilities.dp(42);
            rect.set(AndroidUtilities.dp(24), top, AndroidUtilities.dp(24) + button, top + button);
            drawGlass(canvas, rect, button / 2f, false);
            drawBack(canvas, rect.centerX(), rect.centerY());

            rect.set(AndroidUtilities.dp(74), top, w - AndroidUtilities.dp(24), top + button);
            drawGlass(canvas, rect, button / 2f, false);
            drawAvatar(canvas, rect.left + AndroidUtilities.dp(22), rect.centerY(), AndroidUtilities.dp(16));
            drawText(canvas, "Liquid Glass", rect.left + AndroidUtilities.dp(44), rect.top + AndroidUtilities.dp(17),
                AndroidUtilities.dp(12), Color.WHITE, true);
            drawText(canvas, "онлайн", rect.left + AndroidUtilities.dp(44), rect.top + AndroidUtilities.dp(33),
                AndroidUtilities.dp(10), 0xffb9a7ff, false);

            rect.set(AndroidUtilities.dp(27), AndroidUtilities.dp(78), w * .58f, AndroidUtilities.dp(111));
            drawGlass(canvas, rect, AndroidUtilities.dp(16), false);
            drawText(canvas, "Привет! Как тебе?", rect.left + AndroidUtilities.dp(13), rect.centerY() + AndroidUtilities.dp(4),
                AndroidUtilities.dp(12), Color.WHITE, false);

            rect.set(w * .39f, AndroidUtilities.dp(119), w - AndroidUtilities.dp(27), AndroidUtilities.dp(153));
            drawGlass(canvas, rect, AndroidUtilities.dp(17), true);
            drawText(canvas, "Очень красиво ✨", rect.left + AndroidUtilities.dp(13), rect.centerY() + AndroidUtilities.dp(4),
                AndroidUtilities.dp(12), Color.WHITE, false);

            final float inputHeight = AndroidUtilities.dp(LiteMode.getLiquidGlassInputHeightDp());
            final float inputTop = h - AndroidUtilities.dp(17) - inputHeight;
            final float side = inputHeight;
            rect.set(AndroidUtilities.dp(24), inputTop, AndroidUtilities.dp(24) + side, inputTop + side);
            drawGlass(canvas, rect, side / 2f, false);
            drawPaperclip(canvas, rect.centerX(), rect.centerY());

            rect.set(AndroidUtilities.dp(32) + side, inputTop, w - AndroidUtilities.dp(32) - side, inputTop + inputHeight);
            drawGlass(canvas, rect, inputHeight / 2f, false);
            drawText(canvas, "Сообщение", rect.left + AndroidUtilities.dp(14), rect.centerY() + AndroidUtilities.dp(4),
                AndroidUtilities.dp(12), 0xffc9c9d4, false);

            rect.set(w - AndroidUtilities.dp(24) - side, inputTop, w - AndroidUtilities.dp(24), inputTop + side);
            drawGlass(canvas, rect, side / 2f, true);
            drawSend(canvas, rect.centerX(), rect.centerY());
        }

        private void drawGlow(Canvas canvas, float cx, float cy, float radius, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
        }

        private void drawGlass(Canvas canvas, RectF bounds, float radius, boolean accent) {
            final boolean enabled = LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS);
            final int opacity = LiteMode.getLiquidGlassOpacityLevel();
            final float intensity = LiteMode.getLiquidGlassIntensityScale();
            final int alpha = enabled ? 72 + opacity * 34 : 224;
            final int start = accent ? Color.argb(alpha, 173, 67, 226) : Color.argb(alpha, 31, 35, 47);
            final int end = accent ? Color.argb(alpha, 76, 135, 245) : Color.argb(alpha, 77, 53, 93);

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                start, end, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(bounds, radius, radius, paint);
            paint.setShader(null);

            if (enabled) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dpf2(0.9f + intensity * .45f));
                paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    Color.argb((int) (110 * intensity), 255, 255, 255),
                    Color.argb((int) (26 * intensity), 255, 255, 255), Shader.TileMode.CLAMP));
                canvas.drawRoundRect(bounds, radius, radius, paint);
                paint.setShader(null);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawAvatar(Canvas canvas, float cx, float cy, float radius) {
            paint.setShader(new LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                0xff58d6ff, 0xff9d55f5, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);
            paint.setColor(0x88ffffff);
            canvas.drawCircle(cx - radius * .25f, cy - radius * .2f, radius * .28f, paint);
            rect.set(cx - radius * .52f, cy + radius * .08f, cx + radius * .52f, cy + radius * .58f);
            canvas.drawRoundRect(rect, radius * .35f, radius * .35f, paint);
        }

        private void drawBack(Canvas canvas, float cx, float cy) {
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx + AndroidUtilities.dp(5), cy - AndroidUtilities.dp(8), cx - AndroidUtilities.dp(4), cy, paint);
            canvas.drawLine(cx - AndroidUtilities.dp(4), cy, cx + AndroidUtilities.dp(5), cy + AndroidUtilities.dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawPaperclip(Canvas canvas, float cx, float cy) {
            paint.setColor(0xffe6e6ef);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            rect.set(cx - AndroidUtilities.dp(6), cy - AndroidUtilities.dp(8), cx + AndroidUtilities.dp(6), cy + AndroidUtilities.dp(8));
            canvas.drawOval(rect, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawSend(Canvas canvas, float cx, float cy) {
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx - AndroidUtilities.dp(7), cy + AndroidUtilities.dp(5), cx + AndroidUtilities.dp(7), cy - AndroidUtilities.dp(6), paint);
            canvas.drawLine(cx + AndroidUtilities.dp(7), cy - AndroidUtilities.dp(6), cx + AndroidUtilities.dp(2), cy + AndroidUtilities.dp(7), paint);
            canvas.drawLine(cx - AndroidUtilities.dp(7), cy + AndroidUtilities.dp(5), cx + AndroidUtilities.dp(2), cy + AndroidUtilities.dp(2), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawText(Canvas canvas, String text, float x, float baseline, float size, int color, boolean bold) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(text, x, baseline, paint);
        }
    }
}
