package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** Navigation only: existing screens and preference keys remain unchanged. */
public class BlackHoleGramSettingsActivity extends BaseFragment {
    private static final int ROW_GLASS = 1;
    private static final int ROW_MESSAGES = 2;
    private static final int ROW_UPDATES = 3;
    private static final int ROW_SEARCH = 4;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.BlackHoleGramSettingsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        UniversalRecyclerView listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ROW_SEARCH, R.drawable.outline_header_search, getString(R.string.BHGSettingsSearch)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.BHGAppearanceHeader)));
        items.add(UItem.asButton(ROW_GLASS, R.drawable.msg2_animations, getString(R.string.LiquidGlassSettingsTitle)));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassSettingsInfo)));
        items.add(UItem.asButton(ROW_MESSAGES, R.drawable.msg_photo_curve, getString(R.string.TextAnimationSettingsTitle)));
        items.add(UItem.asShadow(getString(R.string.TextAnimationSettingsInfo)));
        items.add(UItem.asHeader(getString(R.string.BHGToolsHeader)));
        items.add(UItem.asButton(101, R.drawable.msg_customize, getString(R.string.BHGProfiles)));
        items.add(UItem.asButton(102, R.drawable.msg_edit, getString(R.string.BHGNotes)));
        items.add(UItem.asButton(103, R.drawable.msg_notifications, getString(R.string.BHGJournal)));
        items.add(UItem.asButton(104, R.drawable.msg_saved, getString(R.string.BHGTransfer)));
        items.add(UItem.asButton(105, R.drawable.msg_secret, getString(R.string.BHGVault)));
        items.add(UItem.asShadow(getString(R.string.BHGToolsInfo)));
        if (LumaBuildPolicy.allowsBuiltInUpdates()) {
            items.add(UItem.asButton(ROW_UPDATES, R.drawable.settings_features, getString(R.string.LumaUpdatesTitle)));
            items.add(UItem.asShadow(getString(R.string.LumaUpdatesInfo)));
        }
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_GLASS) {
            openSettings(new LiquidGlassSettingsActivity());
        } else if (item.id == ROW_MESSAGES) {
            openSettings(new TextAnimationSettingsActivity());
        } else if (item.id == ROW_UPDATES) {
            if (!LumaBuildPolicy.allowsBuiltInUpdates()) return;
            openSettings(new LumaUpdateActivity());
        } else if (item.id == ROW_SEARCH) {
            openSettings(new BlackHoleSearchActivity());
        } else if (item.id >= 101 && item.id <= 105) {
            openSettings(new BlackHoleToolsActivity(item.id - 100));
        }
    }

    private void openSettings(BaseFragment screen) {
        screen.setCurrentAccount(currentAccount);
        presentFragment(screen);
    }
}
