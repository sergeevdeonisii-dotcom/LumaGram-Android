package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramSubscriptionAccess;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

/** UI explanation only. Each feature must also enforce access at its mutation/effect boundary. */
public final class LunagramFeatureGate {
    private LunagramFeatureGate() {}

    public static boolean require(BaseFragment host, LunagramFeatureCatalog.Feature feature) {
        if (host == null || feature == null) return false;
        final int account = host.getCurrentAccount();
        if (LunagramSubscriptionAccess.canUse(account, feature)) return true;
        LunagramSubscriptionAccess.refresh(account);
        if (host.getParentActivity() == null) return false;
        String id = LunagramFeatureCatalog.minimumTier(feature).id;
        String tier = Character.toUpperCase(id.charAt(0)) + id.substring(1);
        host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.LunagramSubscriptionTitle))
                .setMessage(LocaleController.formatString(R.string.LunagramFeatureRequiresTier, tier))
                .setPositiveButton(LocaleController.getString(R.string.LunagramSubscriptionTitle), (dialog, which) -> {
                    LunagramSubscriptionActivity screen = new LunagramSubscriptionActivity();
                    screen.setCurrentAccount(account);
                    host.presentFragment(screen);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
        return false;
    }
}
