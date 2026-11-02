package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramVideoEffectsSettings;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.utils.settings.SharedSettings;
import java.util.ArrayList;

/** Incumbent rounded native sections; every choice is consumed by the recording pipeline. */
public class LunagramVideoEffectsSettingsActivity extends BaseFragment {
    private UniversalRecyclerView list;
    public static String effectName(int effect) {
        int[] labels = { R.string.LunagramVideoNone, R.string.LunagramVideoVhs, R.string.LunagramVideoGrain,
                R.string.LunagramVideoMono, R.string.LunagramVideoDigital };
        return LocaleController.getString(labels[Math.max(0, Math.min(labels.length - 1, effect))]);
    }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.LunagramVideoEffects));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        list = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        list.setSections();
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(list);
        return fragmentView = frame;
    }
    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.LunagramVideoEffect)));
        items.add(TextDetailCell.Factory.of(1, LocaleController.getString(R.string.LunagramVideoEffect), effectName(LunagramVideoEffectsSettings.getEffect(currentAccount))));
        items.add(UItem.asCheck(2, LocaleController.getString(R.string.LunagramVideoDate)).setChecked(LunagramVideoEffectsSettings.isDateEnabled(currentAccount)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.LunagramVideoEffectsInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.LunagramVideoBokeh)));
        items.add(UItem.asCheck(3, LocaleController.getString(R.string.LunagramVideoBokeh)).setChecked(LunagramVideoEffectsSettings.isBokehEnabled(currentAccount)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.LunagramVideoBokehInfo)));
    }
    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 1) {
            CharSequence[] labels = new CharSequence[5];
            for (int i = 0; i < labels.length; i++) labels[i] = effectName(i);
            showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                    .setTitle(LocaleController.getString(R.string.LunagramVideoEffect))
                    .setItems(labels, (dialog, index) -> {
                        if (index != LunagramVideoEffectsSettings.NONE && !LunagramFeatureGate.require(this, LunagramFeatureCatalog.Feature.ROUND_VIDEO_FILTERS)) return;
                        LunagramVideoEffectsSettings.setEffect(index); update();
                    }).create());
        } else if (item.id == 2) {
            boolean enable = !LunagramVideoEffectsSettings.isDateEnabled(currentAccount);
            if (enable && !LunagramFeatureGate.require(this, LunagramFeatureCatalog.Feature.ROUND_VIDEO_DATE)) return;
            LunagramVideoEffectsSettings.setDateEnabled(enable); update();
        } else if (item.id == 3) {
            boolean enable = !LunagramVideoEffectsSettings.isBokehEnabled(currentAccount);
            if (enable && !LunagramFeatureGate.require(this, LunagramFeatureCatalog.Feature.ROUND_VIDEO_PORTRAIT)) return;
            LunagramVideoEffectsSettings.setBokehEnabled(enable); update();
        }
    }
    private void update() {
        if (LunagramVideoEffectsSettings.needsProcessedPreview(currentAccount)) SharedSettings.roundVideoCamera2Enabled.set(true);
        list.adapter.update(false);
    }
}
