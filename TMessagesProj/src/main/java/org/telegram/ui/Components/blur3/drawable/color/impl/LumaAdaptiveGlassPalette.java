package org.telegram.ui.Components.blur3.drawable.color.impl;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.palette.graphics.Palette;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatBackgroundDrawable;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.MotionBackgroundDrawable;

import java.util.List;
import java.util.WeakHashMap;

public final class LumaAdaptiveGlassPalette {

    private static final WeakHashMap<Drawable, PaletteColors> CACHE = new WeakHashMap<>();

    private LumaAdaptiveGlassPalette() {
    }

    public static int tint(int baseColor, Theme.ResourcesProvider resourcesProvider, boolean bottom) {
        if (!LiteMode.getLiquidGlassAdaptiveColorEnabled()) {
            return baseColor;
        }

        final PaletteColors colors = resolveColors(resourcesProvider);
        int tint = LiteMode.getLiquidGlassSeparateColors()
            ? (bottom ? colors.bottom : colors.top)
            : ColorUtils.blendARGB(colors.top, colors.bottom, 0.5f);
        tint = normalizeTint(tint, resourcesProvider != null
            ? resourcesProvider.isDark() : Theme.isCurrentThemeDark());

        final int alpha = Color.alpha(baseColor);
        final int opaqueBase = ColorUtils.setAlphaComponent(baseColor, 255);
        final int mixed = ColorUtils.blendARGB(opaqueBase, tint, LiteMode.getLiquidGlassColorBlend());
        return ColorUtils.setAlphaComponent(mixed, alpha);
    }

    private static PaletteColors resolveColors(Theme.ResourcesProvider resourcesProvider) {
        Drawable wallpaper = null;
        if (resourcesProvider instanceof ChatActivity.ThemeDelegate) {
            wallpaper = ((ChatActivity.ThemeDelegate) resourcesProvider).getWallpaperDrawable();
        }
        if (wallpaper == null) {
            wallpaper = Theme.getCachedWallpaperNonBlocking();
        }

        wallpaper = unwrap(wallpaper);
        if (wallpaper != null) {
            synchronized (CACHE) {
                final PaletteColors cached = CACHE.get(wallpaper);
                if (cached != null) {
                    return cached;
                }
            }
            final PaletteColors calculated = calculate(wallpaper, resourcesProvider);
            synchronized (CACHE) {
                CACHE.put(wallpaper, calculated);
            }
            return calculated;
        }
        return fallback(resourcesProvider);
    }

    private static Drawable unwrap(Drawable drawable) {
        for (int i = 0; i < 3 && drawable instanceof ChatBackgroundDrawable; i++) {
            final Drawable next = ((ChatBackgroundDrawable) drawable).getDrawable(true);
            if (next == null || next == drawable) {
                break;
            }
            drawable = next;
        }
        return drawable;
    }

    private static PaletteColors calculate(Drawable drawable, Theme.ResourcesProvider resourcesProvider) {
        if (drawable instanceof ColorDrawable) {
            final int color = ColorUtils.setAlphaComponent(((ColorDrawable) drawable).getColor(), 255);
            return new PaletteColors(color, color);
        }
        if (drawable instanceof BackgroundGradientDrawable) {
            final int[] colors = ((BackgroundGradientDrawable) drawable).getColorsList();
            if (colors != null && colors.length > 0) {
                return new PaletteColors(
                    ColorUtils.setAlphaComponent(colors[0], 255),
                    ColorUtils.setAlphaComponent(colors[colors.length - 1], 255)
                );
            }
        }

        Bitmap bitmap = null;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else if (drawable instanceof MotionBackgroundDrawable) {
            bitmap = ((MotionBackgroundDrawable) drawable).getBitmap();
        }
        if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            final int middle = Math.max(1, bitmap.getHeight() / 2);
            final int top = pickBitmapColor(bitmap, 0, middle);
            final int bottom = pickBitmapColor(bitmap, Math.min(middle, bitmap.getHeight() - 1), bitmap.getHeight());
            return new PaletteColors(top, bottom);
        }

        final int[] calculated = AndroidUtilities.calcDrawableColor(drawable);
        if (calculated != null && calculated.length > 0 && calculated[0] != 0) {
            final int color = ColorUtils.setAlphaComponent(calculated[0], 255);
            return new PaletteColors(color, color);
        }
        return fallback(resourcesProvider);
    }

    private static int pickBitmapColor(Bitmap bitmap, int top, int bottom) {
        try {
            if (bottom <= top) {
                return ColorUtils.setAlphaComponent(AndroidUtilities.calcBitmapColor(bitmap), 255);
            }
            final Palette palette = Palette.from(bitmap)
                .setRegion(0, top, bitmap.getWidth(), bottom)
                .resizeBitmapArea(48 * 48)
                .maximumColorCount(12)
                .generate();
            final List<Palette.Swatch> swatches = palette.getSwatches();
            final Palette.Swatch dominant = palette.getDominantSwatch();
            int maxPopulation = dominant != null ? Math.max(1, dominant.getPopulation()) : 1;
            for (int i = 0; i < swatches.size(); i++) {
                maxPopulation = Math.max(maxPopulation, swatches.get(i).getPopulation());
            }

            Palette.Swatch best = dominant;
            float bestScore = -1.0f;
            for (int i = 0; i < swatches.size(); i++) {
                final Palette.Swatch swatch = swatches.get(i);
                final float[] hsl = swatch.getHsl();
                final float population = swatch.getPopulation() / (float) maxPopulation;
                final float balancedLightness = 1.0f - Math.min(1.0f, Math.abs(hsl[2] - 0.5f) * 1.7f);
                final float score = population * 0.58f + hsl[1] * 0.32f + balancedLightness * 0.10f;
                if (score > bestScore) {
                    bestScore = score;
                    best = swatch;
                }
            }
            if (best != null) {
                return ColorUtils.setAlphaComponent(best.getRgb(), 255);
            }
        } catch (Throwable ignore) {
        }
        return ColorUtils.setAlphaComponent(AndroidUtilities.calcBitmapColor(bitmap), 255);
    }

    private static PaletteColors fallback(Theme.ResourcesProvider resourcesProvider) {
        int top = Theme.getColor(Theme.key_chat_wallpaper, resourcesProvider);
        int bottom = top;
        final int[] gradientKeys = {
            Theme.key_chat_wallpaper_gradient_to1,
            Theme.key_chat_wallpaper_gradient_to2,
            Theme.key_chat_wallpaper_gradient_to3
        };
        for (int i = 0; i < gradientKeys.length; i++) {
            final int color = Theme.getColor(gradientKeys[i], resourcesProvider);
            if (Color.alpha(color) != 0 && color != 0) {
                bottom = color;
            }
        }
        if (Color.alpha(top) == 0 || top == 0) {
            top = Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider);
        }
        if (Color.alpha(bottom) == 0 || bottom == 0) {
            bottom = top;
        }
        return new PaletteColors(
            ColorUtils.setAlphaComponent(top, 255),
            ColorUtils.setAlphaComponent(bottom, 255)
        );
    }

    private static int normalizeTint(int color, boolean dark) {
        final float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        if (hsl[1] > 0.06f) {
            hsl[1] = MathUtils.clamp(hsl[1] * 1.08f, 0.18f, 0.74f);
        }
        hsl[2] = dark
            ? MathUtils.clamp(hsl[2], 0.20f, 0.58f)
            : MathUtils.clamp(hsl[2], 0.36f, 0.82f);
        return ColorUtils.HSLToColor(hsl);
    }

    private static final class PaletteColors {
        final int top;
        final int bottom;

        PaletteColors(int top, int bottom) {
            this.top = top;
            this.bottom = bottom;
        }
    }
}
