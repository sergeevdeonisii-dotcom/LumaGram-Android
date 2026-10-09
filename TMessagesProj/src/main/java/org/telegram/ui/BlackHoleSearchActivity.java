package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.BlackHoleSearch;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/** Local catalog only: queries never leave the device. */
public final class BlackHoleSearchActivity extends BaseFragment {
    private static final class Entry {
        final int title, path, destination;
        Entry(int title, int path, int destination) { this.title = title; this.path = path; this.destination = destination; }
    }
    private final ArrayList<Entry> catalog = new ArrayList<>();
    private UniversalRecyclerView listView;
    private String query = "";
    private boolean openedSearch;
    private void group(int destination, int path, int... titles) {
        for (int title : titles) catalog.add(new Entry(title, path, destination));
    }
    @Override public View createView(Context context) {
        openedSearch = false;
        catalog.clear();
        group(1, R.string.LiquidGlassSettingsTitle, R.string.LiquidGlassEnable, R.string.LiquidGlassPowerSaver);
        group(2, R.string.LiquidGlassAdvancedTitle, R.string.LiquidGlassPanelOpacity, R.string.LiquidGlassRefraction,
                R.string.LiquidGlassInputSize, R.string.LiquidGlassWallpaperRefraction, R.string.LiquidGlassAdaptiveColor,
                R.string.LiquidGlassSeparateColors, R.string.LiquidGlassColorStrength, R.string.LiquidGlassColorTransition);
        group(3, R.string.TextAnimationSettingsTitle, R.string.TextAnimationEnable, R.string.LumaAutomaticMessageStyle);
        if (LumaBuildPolicy.allowsBuiltInUpdates()) {
            group(4, R.string.LumaUpdatesTitle, R.string.LumaUpdatesTitle, R.string.LumaUpdateAutomaticHeader);
        }
        if (LumaBuildPolicy.allowsPrivacyTools()) {
            group(101, R.string.BlackHoleAdvancedPrivacyTitle, R.string.ExperimentalGhostEnable,
                    R.string.ExperimentalGhostHeader, R.string.ExperimentalDeletedMessagesEnable);
        }
        group(102, R.string.BlackHoleAdvancedProfileTitle, R.string.ExperimentalStarRatingHeader);
        if (LumaBuildPolicy.allowsAnonymousNumber()) group(102, R.string.BlackHoleAdvancedProfileTitle, R.string.ExperimentalAnonymousNumberHeader);
        if (LumaBuildPolicy.allowsProfileVerification()) group(102, R.string.BlackHoleAdvancedProfileTitle, R.string.ExperimentalProfileVerificationHeader);
        group(103, R.string.BlackHoleAdvancedTypingTitle, R.string.ExperimentalTypingSpeed,
                R.string.ExperimentalTypingBlur, R.string.ExperimentalTypingHeight, R.string.ExperimentalTypingSwipe);
        group(5, R.string.RoundVideoSettings, R.string.RoundVideoSettings,
                R.string.LumaRoundVideoQualityEnable, R.string.LumaRoundVideoStartRearCamera,
                R.string.LumaRoundVideoFps, R.string.LumaRoundVideoStabilization,
                R.string.RoundVideoOutputResolution, R.string.RoundVideoCameraResolution,
                R.string.RoundVideoBitrate, R.string.LumaRoundVideoMeasuredTitle);
        if (LumaBuildPolicy.allowsPrivacyTools()) group(104, R.string.BlackHoleAdvancedSendingTitle, R.string.ExperimentalDelayedSendEnable);
        group(105, R.string.BlackHoleAdvancedConnectionTitle, R.string.EmergencyConnectionHeader, R.string.EmergencyConnectionChat);
        group(201, R.string.BlackHoleGramSettingsTitle, R.string.BHGProfiles);
        group(202, R.string.BlackHoleGramSettingsTitle, R.string.BHGNotes);
        group(203, R.string.BlackHoleGramSettingsTitle, R.string.BHGJournal);
        group(204, R.string.BlackHoleGramSettingsTitle, R.string.BHGTransfer, R.string.BHGExport, R.string.BHGImport);
        group(205, R.string.BlackHoleGramSettingsTitle, R.string.BHGVault);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.BHGSettingsSearch));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ActionBarMenuItem search = actionBar.createMenu().addItem(0, R.drawable.outline_header_search);
        search.setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override public void onTextChanged(EditText field) { query = field.getText().toString(); if (listView != null) listView.adapter.update(false); }
            @Override public void onSearchCollapse() { query = ""; if (listView != null) listView.adapter.update(false); }
        });
        search.setSearchFieldHint(getString(R.string.BHGSettingsSearch));
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView = new UniversalRecyclerView(this, this::fill, this::click, null); listView.setSections();
        root.addView(listView, LayoutHelper.createFrame(-1, -1, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = root;
    }
    private void fill(ArrayList<UItem> items, UniversalAdapter adapter) {
        for (int n = 0; n < catalog.size(); n++) {
            Entry e = catalog.get(n);
            String name = getString(e.title), path = getString(e.path);
            if (BlackHoleSearch.matches(query, name + " " + path)) items.add(TextDetailCell.Factory.of(n + 1, name, path));
        }
        if (items.isEmpty()) items.add(UItem.asShadow(getString(R.string.BHGSearchEmpty)));
    }
    private void click(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > catalog.size()) return;
        int destination = catalog.get(item.id - 1).destination;
        if (destination == 4 && !LumaBuildPolicy.allowsBuiltInUpdates()) return;
        if ((destination == 101 || destination == 104) && !LumaBuildPolicy.allowsPrivacyTools()) return;
        BaseFragment screen;
        if (destination == 1 || destination == 2) screen = new LiquidGlassSettingsActivity(destination == 2);
        else if (destination == 3) screen = new TextAnimationSettingsActivity();
        else if (destination == 4) screen = new LumaUpdateActivity();
        else if (destination == 5) screen = new RoundVideoSettingsActivity();
        else if (destination >= 201) screen = new BlackHoleToolsActivity(destination - 200);
        else screen = ExperimentalFeaturesActivity.forSection(destination);
        screen.setCurrentAccount(currentAccount); presentFragment(screen);
    }
    @Override public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !openedSearch && actionBar != null) { openedSearch = true; actionBar.openSearchField(query, true); }
    }
}
