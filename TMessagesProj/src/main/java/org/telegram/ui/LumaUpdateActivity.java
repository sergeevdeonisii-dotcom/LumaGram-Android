package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BetaUpdate;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LumaUpdaterController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;

public class LumaUpdateActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_CHECK = 1;
    private static final int ROW_AUTO = 2;
    private static final int ROW_SOURCE = 3;
    private static final int ROW_UPDATE = 4;

    private UniversalRecyclerView listView;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateLoading);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateLoading);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.LumaUpdatesTitle));
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
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        BetaUpdate update = controller.getUpdate();
        File downloaded = controller.getDownloadedFile();

        items.add(UItem.asHeader(getString(R.string.LumaUpdatesTitle)));
        items.add(UItem.asButton(0, getString(R.string.LumaUpdateCurrentVersion), BuildVars.BUILD_VERSION_STRING).setEnabled(false));
        if (update != null) {
            String title;
            String value;
            if (downloaded != null) {
                title = getString(R.string.LumaUpdateInstall);
                value = update.version;
            } else if (controller.isDownloading()) {
                title = getString(R.string.LumaUpdateDownloadingShort);
                value = Math.round(controller.getDownloadingProgress() * 100) + "%";
            } else {
                title = getString(R.string.LumaUpdateDownloadInstall);
                value = update.version;
            }
            items.add(UItem.asButton(ROW_UPDATE, title, value).accent());
        }
        items.add(UItem.asButton(ROW_CHECK, getString(controller.isChecking() ? R.string.LumaUpdateChecking : R.string.LumaUpdateCheckNow)).accent());
        items.add(UItem.asShadow(getString(R.string.LumaUpdateSecureInfo)));

        items.add(UItem.asHeader(getString(R.string.LumaUpdateAutomaticHeader)));
        items.add(UItem.asCheck(ROW_AUTO, getString(R.string.LumaUpdateAutomatic)).setChecked(controller.isAutoCheckEnabled()));
        items.add(UItem.asShadow(getString(R.string.LumaUpdateAutomaticInfo)));

        items.add(UItem.asHeader(getString(R.string.LumaUpdateAdvancedHeader)));
        items.add(UItem.asButton(ROW_SOURCE, getString(R.string.LumaUpdateSource), sourceLabel(controller.getManifestUrl())));
        items.add(UItem.asShadow(getString(R.string.LumaUpdateSourceInfo)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        if (item.id == ROW_AUTO) {
            boolean enabled = !controller.isAutoCheckEnabled();
            controller.setAutoCheckEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ROW_SOURCE) {
            showSourceDialog();
        } else if (item.id == ROW_CHECK) {
            if (!controller.hasManifestUrl()) {
                showSourceDialog();
            } else {
                checkNow();
            }
        } else if (item.id == ROW_UPDATE) {
            BetaUpdate update = controller.getUpdate();
            if (update != null && !controller.isDownloading()) {
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(getContext(), update, currentAccount);
            }
        }
    }

    private void checkNow() {
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        AlertDialog progressDialog = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.show();
        controller.checkForUpdate(true, () -> {
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            updateList();
            if (!TextUtils.isEmpty(controller.getLastError())) {
                new AlertDialog.Builder(getContext(), resourceProvider)
                        .setTitle(getString(R.string.LumaUpdatesTitle))
                        .setMessage(controller.getLastError())
                        .setPositiveButton(getString(R.string.OK), null)
                        .show();
                return;
            }
            BetaUpdate update = controller.getUpdate();
            if (update == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.YourVersionIsLatest)).show();
            } else {
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(getContext(), update, currentAccount);
            }
        });
        updateList();
    }

    private void showSourceDialog() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(controller.getManifestUrl());
        input.setSelection(input.length());
        input.setHint("https://…/latest.json");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider));
        input.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.getColor(Theme.key_dialogBackgroundGray, resourceProvider)));
        input.setPadding(dp(12), 0, dp(12), 0);

        FrameLayout container = new FrameLayout(context);
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.FILL_HORIZONTAL, 4, 4, 4, 4));
        new AlertDialog.Builder(context, resourceProvider)
                .setTitle(getString(R.string.LumaUpdateSource))
                .setMessage(getString(R.string.LumaUpdateSourceDialogInfo))
                .setView(container, 56)
                .setPositiveButton(getString(R.string.Save), (dialog, which) -> {
                    if (!controller.setManifestUrl(input.getText().toString())) {
                        new AlertDialog.Builder(context, resourceProvider)
                                .setTitle(getString(R.string.LumaUpdatesTitle))
                                .setMessage(getString(R.string.LumaUpdateSourceInvalid))
                                .setPositiveButton(getString(R.string.OK), null)
                                .show();
                    }
                    updateList();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
        input.requestFocus();
    }

    private static String sourceLabel(String url) {
        if (TextUtils.isEmpty(url)) {
            return LocaleController.getString(R.string.LumaUpdateNotConfigured);
        }
        try {
            String host = new URI(url).getHost();
            return TextUtils.isEmpty(host) ? url : host;
        } catch (Exception ignore) {
            return url;
        }
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appUpdateAvailable || id == NotificationCenter.appUpdateLoading) {
            updateList();
        }
    }
}
