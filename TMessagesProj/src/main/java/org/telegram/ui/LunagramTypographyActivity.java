package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LunagramTypography;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LunagramTypographyViews;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

public final class LunagramTypographyActivity extends BaseFragment {
    private UniversalRecyclerView list;
    private LinearLayout preview;
    private TextView previewTitle, previewBody;

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.LufTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(18));
        LunagramTypographyViews.ignore(preview);
        previewTitle = new TextView(context);
        previewTitle.setTextSize(18);
        previewTitle.setText(getString(R.string.LufPreviewTitle));
        previewTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        preview.addView(previewTitle, LayoutHelper.createLinear(-1, -2));
        previewBody = new TextView(context);
        previewBody.setTextSize(17);
        previewBody.setText(getString(R.string.LufPreviewText));
        previewBody.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        preview.addView(previewBody, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 0));
        list = new UniversalRecyclerView(this, this::fill, this::click, null);
        list.setSections();
        root.addView(list, LayoutHelper.createFrame(-1, -1, Gravity.FILL));
        actionBar.setAdaptiveBackground(list);
        updatePreview();
        return fragmentView = root;
    }

    private String[] families() {
        return new String[]{getString(R.string.LufSystem), getString(R.string.LufSans), getString(R.string.LufSerif), getString(R.string.LufMono), getString(R.string.LufCondensed)};
    }
    private String[] weights() { return new String[]{getString(R.string.LufNormal), getString(R.string.LufMedium), getString(R.string.LufBold)}; }

    private void fill(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(TextDetailCell.Factory.of(1, getString(R.string.LufFamily), families()[LunagramTypography.family()]));
        items.add(TextDetailCell.Factory.of(2, getString(R.string.LufWeight), weights()[LunagramTypography.weight()]));
        items.add(UItem.asShadow(getString(R.string.LufInfo)));
        items.add(UItem.asHeader(getString(R.string.LufPreview)));
        items.add(UItem.asCustom(preview));
        items.add(UItem.asShadow(getString(LunagramTypography.requiresRestart() ? R.string.LufRestart : R.string.LufCurrent)));
        items.add(UItem.asButton(3, R.drawable.msg_reset, getString(R.string.LufReset)));
        items.add(UItem.asShadow(null));
    }

    private void click(UItem item, View view, int position, float x, float y) {
        if (item.id == 1 || item.id == 2) {
            boolean family = item.id == 1;
            showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), family ? families() : weights(),
                    getString(family ? R.string.LufFamily : R.string.LufWeight), family ? LunagramTypography.family() : LunagramTypography.weight(),
                    (dialog, which) -> {
                        LunagramTypography.set(family ? which : LunagramTypography.family(), family ? LunagramTypography.weight() : which);
                        updatePreview();
                        list.adapter.update(false);
                    }));
        } else if (item.id == 3) {
            LunagramTypography.reset();
            updatePreview();
            list.adapter.update(false);
        }
    }

    private void updatePreview() {
        previewTitle.setTypeface(LunagramTypography.preview(true));
        previewBody.setTypeface(LunagramTypography.preview(false));
    }
}
