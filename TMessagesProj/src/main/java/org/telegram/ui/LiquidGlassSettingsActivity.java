package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public class LiquidGlassSettingsActivity extends BaseFragment {

    private static final int ROW_ENABLED = 1;
    private static final int ROW_POWER_SAVER = 2;
    private static final int ROW_RESET = 3;
    private static final int ROW_ADAPTIVE_COLOR = 4;
    private static final int ROW_SEPARATE_COLORS = 5;
    private static final int ROW_WALLPAPER_REFRACTION = 6;

    private UniversalRecyclerView listView;
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

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS);
        final boolean adaptiveColor = LiteMode.getLiquidGlassAdaptiveColorEnabled();
        final boolean wallpaperRefraction = LiteMode.getLiquidGlassWallpaperRefractionEnabled();

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
        }).setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassRefraction)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassSoft),
            getString(R.string.LiquidGlassBalanced),
            getString(R.string.LiquidGlassStrong)
        }, LiteMode.getLiquidGlassIntensityLevel(), value -> {
            LiteMode.setLiquidGlassIntensityLevel(value);
            settingsChanged = true;
        }).setEnabled(enabled));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassInputSize)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassThin),
            getString(R.string.LiquidGlassMedium),
            getString(R.string.LiquidGlassLarge)
        }, LiteMode.getLiquidGlassInputSizeLevel(), value -> {
            LiteMode.setLiquidGlassInputSizeLevel(value);
            settingsChanged = true;
        }).setEnabled(enabled));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassAdvancedInfo)));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassWallpaperRefraction)));
        items.add(UItem.asCheck(ROW_WALLPAPER_REFRACTION, getString(R.string.LiquidGlassWallpaperRefractionEnable))
            .setChecked(wallpaperRefraction)
            .setEnabled(enabled));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassWallpaperRefractionInfo)));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassAdaptiveColor)));
        items.add(UItem.asCheck(ROW_ADAPTIVE_COLOR, getString(R.string.LiquidGlassAdaptiveColorEnable))
            .setChecked(adaptiveColor)
            .setEnabled(enabled));
        items.add(UItem.asCheck(ROW_SEPARATE_COLORS, getString(R.string.LiquidGlassSeparateColors))
            .setChecked(LiteMode.getLiquidGlassSeparateColors())
            .setEnabled(enabled && adaptiveColor));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassAdaptiveColorInfo)));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassColorStrength)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassSoft),
            getString(R.string.LiquidGlassBalanced),
            getString(R.string.LiquidGlassStrong)
        }, LiteMode.getLiquidGlassColorStrengthLevel(), value -> {
            LiteMode.setLiquidGlassColorStrengthLevel(value);
            settingsChanged = true;
        }).setEnabled(enabled && adaptiveColor));

        items.add(UItem.asHeader(getString(R.string.LiquidGlassColorTransition)));
        items.add(UItem.asSlideView(new String[] {
            getString(R.string.LiquidGlassTransitionFast),
            getString(R.string.LiquidGlassTransitionSmooth),
            getString(R.string.LiquidGlassTransitionGentle)
        }, LiteMode.getLiquidGlassColorTransitionLevel(), value -> {
            LiteMode.setLiquidGlassColorTransitionLevel(value);
            settingsChanged = true;
        }).setEnabled(enabled && adaptiveColor));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassColorTransitionInfo)));
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
            listView.adapter.update(false);
        } else if (item.id == ROW_POWER_SAVER) {
            final boolean enabled = !LiteMode.getLiquidGlassKeepInPowerSaver();
            LiteMode.setLiquidGlassKeepInPowerSaver(enabled);
            settingsChanged = true;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ROW_ADAPTIVE_COLOR) {
            final boolean enabled = !LiteMode.getLiquidGlassAdaptiveColorEnabled();
            if (enabled && LiteMode.getLiquidGlassWallpaperRefractionEnabled()) {
                showConflict(R.string.LiquidGlassWallpaperRefraction);
                return;
            }
            LiteMode.setLiquidGlassAdaptiveColorEnabled(enabled);
            settingsChanged = true;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            listView.adapter.update(false);
        } else if (item.id == ROW_WALLPAPER_REFRACTION) {
            final boolean enabled = !LiteMode.getLiquidGlassWallpaperRefractionEnabled();
            if (enabled && LiteMode.getLiquidGlassAdaptiveColorEnabled()) {
                showConflict(R.string.LiquidGlassAdaptiveColor);
                return;
            }
            LiteMode.setLiquidGlassWallpaperRefractionEnabled(enabled);
            settingsChanged = true;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            listView.adapter.update(false);
        } else if (item.id == ROW_SEPARATE_COLORS) {
            final boolean enabled = !LiteMode.getLiquidGlassSeparateColors();
            LiteMode.setLiquidGlassSeparateColors(enabled);
            settingsChanged = true;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ROW_RESET) {
            LiteMode.resetLiquidGlassSettings();
            settingsChanged = true;
            listView.adapter.update(false);
        }
    }

    private void showConflict(int featureNameResId) {
        BulletinFactory.of(this).createSimpleBulletin(
            R.raw.info,
            LocaleController.formatString(
                R.string.LiquidGlassFeatureConflict,
                getString(featureNameResId)
            )
        ).show();
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
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }
}
