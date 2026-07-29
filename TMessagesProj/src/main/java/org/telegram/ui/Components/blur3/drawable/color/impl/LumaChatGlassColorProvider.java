package org.telegram.ui.Components.blur3.drawable.color.impl;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

public class LumaChatGlassColorProvider implements BlurredBackgroundColorProvider {

    private final Theme.ResourcesProvider resourcesProvider;
    private final int currentAccount;

    private int backgroundColor;
    private int shadowColor;
    private int strokeColorTop;
    private int strokeColorBottom;

    public LumaChatGlassColorProvider(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        this.currentAccount = currentAccount;
        this.resourcesProvider = resourcesProvider;
        updateColors();
    }

    public void updateColors() {
        final int themedColor = Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider);
        if (!BlurredBackgroundProviderImpl.checkBlurEnabled(currentAccount, resourcesProvider)) {
            backgroundColor = themedColor | 0xFF000000;
            updateChrome(themedColor);
            return;
        }

        final boolean dark = resourcesProvider != null
            ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        if (!LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            backgroundColor = Theme.multAlpha(themedColor, dark ? 0.76f : 216.0f / 255.0f);
            updateChrome(themedColor);
            return;
        }
        final int tintedColor = LumaAdaptiveGlassPalette.tint(themedColor, resourcesProvider, true);
        final float alpha = LiteMode.getLiquidGlassChatPanelAlpha(dark);
        backgroundColor = Theme.multAlpha(tintedColor, alpha);
        updateChrome(tintedColor);
    }

    private void updateChrome(int color) {
        if (AndroidUtilities.computePerceivedBrightness(color) < 0.721f) {
            strokeColorTop = 0x20FFFFFF;
            strokeColorBottom = 0x14FFFFFF;
            shadowColor = 0;
        } else {
            strokeColorTop = 0xFFFFFFFF;
            strokeColorBottom = 0xFFFFFFFF;
            shadowColor = 0x20000000;
        }
    }

    @Override
    public int getShadowColor() {
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        return strokeColorBottom;
    }

    @Override
    public int getColorTransitionDuration() {
        return LiteMode.getLiquidGlassColorTransitionDuration();
    }
}
