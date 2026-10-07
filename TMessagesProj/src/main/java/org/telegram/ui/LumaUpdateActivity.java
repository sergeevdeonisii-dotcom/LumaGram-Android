package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BetaUpdate;
import org.telegram.messenger.BuildVars;
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
import java.util.ArrayList;

public class LumaUpdateActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_CHECK = 1;
    private static final int ROW_AUTO = 2;
    private static final int ROW_UPDATE = 4;
    private static final int ROW_REPAIR = 5;

    private UniversalRecyclerView listView;
    private AlertDialog checkProgressDialog;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateLoading);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (checkProgressDialog != null) {
            AlertDialog dialog = checkProgressDialog;
            checkProgressDialog = null;
            dialog.dismiss();
        }
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
        items.add(UItem.asButton(ROW_CHECK, getString(controller.isChecking() ? R.string.LumaUpdateChecking : R.string.LumaUpdateCheckNow))
                .accent().setEnabled(!controller.isChecking()));
        items.add(UItem.asShadow(getString(R.string.LumaUpdateSecureInfo)));

        items.add(UItem.asHeader(getString(R.string.LumaUpdateAutomaticHeader)));
        items.add(UItem.asCheck(ROW_AUTO, getString(R.string.LumaUpdateAutomatic)).setChecked(controller.isAutoCheckEnabled()));
        items.add(UItem.asShadow(getString(R.string.LumaUpdateAutomaticInfo)));
        items.add(UItem.asButton(ROW_REPAIR, getString(R.string.LumaUpdateRepairSource)));
        items.add(UItem.asShadow(null));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        if (item.id == ROW_AUTO) {
            boolean enabled = !controller.isAutoCheckEnabled();
            controller.setAutoCheckEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
        } else if (item.id == ROW_REPAIR) {
            new AlertDialog.Builder(getContext(), resourceProvider)
                    .setTitle(getString(R.string.LumaUpdateRepairSource))
                    .setMessage(getString(R.string.LumaUpdateRepairSourceInfo))
                    .setPositiveButton(getString(R.string.LumaUpdateRestoreAction), (dialog, which) -> {
                        controller.setManifestUrl(null);
                        checkNow();
                    })
                    .setNegativeButton(getString(R.string.Cancel), null).show();
        } else if (item.id == ROW_CHECK) {
            checkNow();
        } else if (item.id == ROW_UPDATE) {
            BetaUpdate update = controller.getUpdate();
            if (update != null && !controller.isDownloading()) {
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(getContext(), update, currentAccount);
            }
        }
    }

    private void checkNow() {
        if (checkProgressDialog != null || getContext() == null || getParentActivity() == null) return;
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        AlertDialog progressDialog = new AlertDialog(getContext(), AlertDialog.ALERT_TYPE_SPINNER);
        final String checkedSource = controller.getManifestUrl();
        checkProgressDialog = progressDialog;
        if (showDialog(progressDialog, dismissed -> {
            if (checkProgressDialog == progressDialog) checkProgressDialog = null;
        }) == null) {
            checkProgressDialog = null;
            return;
        }
        controller.checkForUpdate(true, () -> {
            // Cancel, pause and destroy only dismiss this screen's UI, not a shared check.
            boolean showResult = checkProgressDialog == progressDialog;
            if (showResult) checkProgressDialog = null;
            try {
                progressDialog.dismiss();
            } catch (Exception ignore) {
            }
            if (!showResult || isFinished || isPaused() || getContext() == null || getParentActivity() == null) return;
            updateList();
            if (!TextUtils.equals(checkedSource, controller.getManifestUrl())) return;
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
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.LumaUpdateSourceNoNewVersion)).show();
            } else {
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(getContext(), update, currentAccount);
            }
        });
        updateList();
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
