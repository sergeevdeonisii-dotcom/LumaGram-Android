package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
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
        items.add(UItem.asButton(ROW_GLASS, R.drawable.msg2_animations, getString(R.string.LiquidGlassSettingsTitle)));
        items.add(UItem.asShadow(getString(R.string.LiquidGlassSettingsInfo)));
        items.add(UItem.asButton(ROW_MESSAGES, R.drawable.msg_photo_curve, getString(R.string.TextAnimationSettingsTitle)));
        items.add(UItem.asShadow(getString(R.string.TextAnimationSettingsInfo)));
        items.add(UItem.asButton(ROW_UPDATES, R.drawable.settings_features, getString(R.string.LumaUpdatesTitle)));
        items.add(UItem.asShadow(getString(R.string.LumaUpdatesInfo)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ROW_GLASS) {
            openSettings(new LiquidGlassSettingsActivity());
        } else if (item.id == ROW_MESSAGES) {
            openSettings(new TextAnimationSettingsActivity());
        } else if (item.id == ROW_UPDATES) {
            openSettings(new LumaUpdateActivity());
        }
    }

    private void openSettings(BaseFragment screen) {
        screen.setCurrentAccount(currentAccount);
        presentFragment(screen);
    }
}
