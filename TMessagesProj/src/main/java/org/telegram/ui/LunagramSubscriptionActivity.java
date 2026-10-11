package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramSubscriptionCatalog;
import org.telegram.messenger.LunagramSubscriptionCatalog.PaymentMethod;
import org.telegram.messenger.LunagramSubscriptionCatalog.Plan;
import org.telegram.messenger.LunagramSubscriptionClient;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LumaDialogInput;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** Optional, server-confirmed subscription; existing client features remain ungated. */
public final class LunagramSubscriptionActivity extends BaseFragment {
    private static final int PLAN_ROW_START = 100;
    private static final int ROW_CRYPTO_BOT = 200;
    private static final int ROW_TON = 201;
    private static final int ROW_PAY = 202;
    private static final int ROW_STARS = 203;
    private static final int ROW_ACTIVATE_CODE = 300;
    private static final int ROW_CONNECT = 400;
    private static final int ROW_CHECK = 401;
    private static final int ROW_CANCEL_AUTH = 402;
    private static final int ROW_DISCONNECT = 403;

    private Plan selectedPlan = Plan.BASED;
    private PaymentMethod selectedMethod = PaymentMethod.TELEGRAM_STARS;
    private UniversalRecyclerView listView;
    private LunagramSubscriptionClient client;
    private String openedPairUrl;
    private boolean statusRequested;
    private AlertDialog activationDialog;
    private EditTextBoldCursor activationField;
    private boolean redeemPending;
    private final Runnable poll = () -> {
        if (client != null && !isPaused && !isFinished && client.pairExpiresAtMs() > 0) client.checkAuthentication();
    };

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        client = new LunagramSubscriptionClient(currentAccount, this::onSubscriptionChanged);
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (client == null) return;
        if (client.isTestAccount()) { client.dispose(); onSubscriptionChanged(); return; }
        if (!client.isAccountCurrent()) { client.dispose(); finishFragment(); return; }
        if (client.isPairing()) client.checkAuthentication();
        else if (client.isAuthenticated()) { statusRequested = true; client.refresh(); }
        else { statusRequested = false; client.restore(); }
        onSubscriptionChanged();
    }

    @Override
    public void onPause() {
        AndroidUtilities.cancelRunOnUIThread(poll);
        redeemPending = false;
        if (client != null) client.pause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (activationDialog != null) activationDialog.dismiss();
        if (client != null) client.dispose();
        client = null;
        super.onFragmentDestroy();
    }

    private void onSubscriptionChanged() {
        if (client == null || isFinished) return;
        if (listView != null) listView.adapter.update(false);
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (redeemPending && !client.isBusy()) {
            redeemPending = false;
            if (client.error() == LunagramSubscriptionClient.Error.NONE) {
                if (activationDialog != null) activationDialog.dismiss();
            } else if (activationField != null) activationField.setError(errorText());
        }
        if (isPaused) return;
        if (client.isAuthenticated() && client.subscription() == null && !client.isBusy() && !statusRequested) {
            statusRequested = true; client.refresh(); return;
        }
        String url = client.authenticationUrl();
        if (url != null && !url.equals(openedPairUrl) && getParentActivity() != null) {
            openedPairUrl = url;
            Browser.openUrl(getParentActivity(), url, false);
        }
        if (client.isPairing() && !client.isBusy() && client.error() == LunagramSubscriptionClient.Error.NONE) {
            AndroidUtilities.runOnUIThread(poll, 3000);
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.LunagramSubscriptionTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = content;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionAccessInfo)));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionAccount)));
        items.add(UItem.asShadow(connectionText()));
        if (client != null && client.isAuthenticated()) {
            items.add(UItem.asButton(ROW_CHECK, getString(R.string.LunagramSubscriptionRefresh)).setEnabled(!client.isBusy()));
            items.add(UItem.asButton(ROW_DISCONNECT, getString(R.string.LunagramSubscriptionDisconnect)).setEnabled(!client.isBusy()));
        } else if (client != null && client.isPairing()) {
            items.add(UItem.asButton(ROW_CHECK, getString(R.string.LunagramSubscriptionCheck)).setEnabled(!client.isBusy()));
            items.add(UItem.asButton(ROW_CANCEL_AUTH, getString(R.string.Cancel)));
        } else {
            items.add(UItem.asButton(ROW_CONNECT, getString(R.string.LunagramSubscriptionConnect))
                    .setEnabled(client != null && client.isAccountCurrent() && !client.isBusy()));
        }
        if (client != null && client.error() != LunagramSubscriptionClient.Error.NONE) items.add(UItem.asShadow(errorText()));
        if (client != null && !client.isPersistentStorageAvailable()) items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionEphemeral)));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionPlans)));
        for (int i = 0; i < LunagramSubscriptionCatalog.PLANS.size(); i++) {
            Plan plan = LunagramSubscriptionCatalog.PLANS.get(i);
            // The native two-line radio cell wraps the price and exposes checked state to TalkBack.
            items.add(UItem.asRadio2(PLAN_ROW_START + i, plan.title,
                    LocaleController.formatString(R.string.LunagramSubscriptionStarsPrice, plan.monthlyStars, plan.monthlyUsdAmount()))
                    .setChecked(plan == selectedPlan));
        }
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionRenewalInfo)));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionPaymentMethod)));
        items.add(UItem.asRadio2(ROW_STARS, getString(R.string.LunagramSubscriptionStars),
                getString(R.string.LunagramSubscriptionStarsInfo))
                .setChecked(selectedMethod == PaymentMethod.TELEGRAM_STARS));
        items.add(UItem.asRadio2(ROW_CRYPTO_BOT, getString(R.string.LunagramSubscriptionCryptoBot),
                getString(R.string.LunagramSubscriptionCryptoBotInfo))
                .setChecked(selectedMethod == PaymentMethod.CRYPTO_BOT_USDT));
        items.add(UItem.asRadio2(ROW_TON, getString(R.string.LunagramSubscriptionTon),
                getString(R.string.LunagramSubscriptionTonInfo))
                .setChecked(selectedMethod == PaymentMethod.TON));
        items.add(UItem.asButton(ROW_PAY, getString(selectedMethod == PaymentMethod.TELEGRAM_STARS
                        ? R.string.LunagramSubscriptionBuyInBot : R.string.LunagramSubscriptionPaymentUnavailable))
                .setEnabled(selectedMethod == PaymentMethod.TELEGRAM_STARS && client != null && client.isAccountCurrent()));
        items.add(UItem.asShadow(getString(selectedMethod == PaymentMethod.TELEGRAM_STARS
                ? R.string.LunagramSubscriptionStarsCheckout : R.string.LunagramSubscriptionUnavailableInfo)));

        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionCodes)));
        items.add(UItem.asButton(ROW_ACTIVATE_CODE, getString(R.string.LunagramSubscriptionActivateCode))
                .setEnabled(client != null && client.isAuthenticated() && !client.isBusy()));
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionConnectToActivate)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (client == null || !client.isAccountCurrent()) return;
        if (item.id >= PLAN_ROW_START && item.id < PLAN_ROW_START + LunagramSubscriptionCatalog.PLANS.size()) {
            selectedPlan = LunagramSubscriptionCatalog.PLANS.get(item.id - PLAN_ROW_START);
            listView.adapter.update(false);
        } else if (item.id == ROW_STARS || item.id == ROW_CRYPTO_BOT || item.id == ROW_TON) {
            selectedMethod = item.id == ROW_STARS ? PaymentMethod.TELEGRAM_STARS
                    : item.id == ROW_TON ? PaymentMethod.TON : PaymentMethod.CRYPTO_BOT_USDT;
            listView.adapter.update(false);
        } else if (item.id == ROW_CONNECT && client != null) {
            openedPairUrl = null; statusRequested = false; client.startAuthentication();
        } else if (item.id == ROW_CHECK && client != null) {
            if (client.isPairing()) client.checkAuthentication();
            else { statusRequested = true; client.refresh(); }
        } else if (item.id == ROW_CANCEL_AUTH && client != null) {
            client.cancelAuthentication();
        } else if (item.id == ROW_DISCONNECT && client != null) {
            if (getParentActivity() == null) return;
            showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                    .setTitle(getString(R.string.LunagramSubscriptionDisconnect))
                    .setMessage(getString(R.string.LunagramSubscriptionDisconnectInfo))
                    .setPositiveButton(getString(R.string.LunagramSubscriptionDisconnect), (d, which) -> {
                        if (client != null) client.disconnect();
                    })
                    .setNegativeButton(getString(R.string.Cancel), null).create());
        } else if (item.id == ROW_PAY && selectedMethod == PaymentMethod.TELEGRAM_STARS) {
            if (getParentActivity() != null) Browser.openUrl(getParentActivity(), LunagramSubscriptionClient.purchaseUrl(selectedPlan), false);
        } else if (item.id == ROW_ACTIVATE_CODE && client != null && client.isAuthenticated()) {
            showActivationDialog();
        }
    }

    private String connectionText() {
        if (client != null && client.isTestAccount()) return getString(R.string.LunagramSubscriptionTestAccount);
        String account = LocaleController.formatString(R.string.LunagramSubscriptionAccountId,
                Long.toString(UserConfig.getInstance(currentAccount).getClientUserId()));
        if (client == null || client.isBusy()) return account + "\n" + getString(R.string.LunagramSubscriptionLoading);
        if (client.isPairing()) return account + "\n" + getString(R.string.LunagramSubscriptionPending);
        if (!client.isAuthenticated()) return account + "\n" + getString(R.string.LunagramSubscriptionDisconnected);
        LunagramSubscriptionClient.Subscription subscription = client.subscription();
        if (subscription == null) return account + "\n" + getString(R.string.LunagramSubscriptionConnected);
        if (!subscription.active || subscription.expiresAtMs <= System.currentTimeMillis()) return account + "\n" + getString(R.string.LunagramSubscriptionInactive);
        String title = subscription.planId;
        for (Plan plan : LunagramSubscriptionCatalog.PLANS) if (plan.id.equals(subscription.planId)) title = plan.title;
        return account + "\n" + LocaleController.formatString(R.string.LunagramSubscriptionActive, title,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(subscription.expiresAtMs)));
    }

    private String errorText() {
        if (client == null) return "";
        switch (client.error()) {
            case ACCOUNT_CHANGED: return getString(R.string.LunagramSubscriptionAccountChanged);
            case AUTH_REQUIRED: return getString(R.string.LunagramSubscriptionAuthRequired);
            case EXPIRED: return getString(R.string.LunagramSubscriptionExpired);
            case STORAGE: return getString(R.string.LunagramSubscriptionStorageError);
            case INVALID_CODE: return getString(R.string.LunagramSubscriptionCodeRejected);
            case RATE_LIMITED: return getString(R.string.LunagramSubscriptionRateLimited);
            case NOT_CONFIGURED: return getString(R.string.LunagramSubscriptionUnavailableInfo);
            case BAD_RESPONSE: return getString(R.string.LunagramSubscriptionResponseError);
            default: return getString(R.string.LunagramSubscriptionNetworkError);
        }
    }

    private void showActivationDialog() {
        if (getParentActivity() == null) return;
        EditTextBoldCursor field = LumaDialogInput.create(getParentActivity(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                false, resourceProvider);
        field.setHint(getString(R.string.LunagramSubscriptionCodeHint));
        field.setContentDescription(getString(R.string.LunagramSubscriptionActivateCode));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LunagramSubscriptionCatalog.MAX_CODE_INPUT_LENGTH)});
        field.setSaveEnabled(false);
        field.setTextDirection(View.TEXT_DIRECTION_LTR);
        field.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }

        Runnable validate = () -> {
            if (client == null || client.isBusy() || !client.isAuthenticated()) return;
            String code = LunagramSubscriptionCatalog.normalizeActivationCode(field.getText());
            if (code == null) {
                field.setError(getString(R.string.LunagramSubscriptionCodeInvalid));
                return;
            }
            redeemPending = true;
            client.redeem(code);
            field.setText("");
        };
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.LunagramSubscriptionActivateCode))
                .setMessage(getString(R.string.LunagramSubscriptionCodeInfo))
                .setView(LumaDialogInput.wrap(field))
                .setPositiveButton(getString(R.string.Continue), (d, which) -> validate.run())
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> d.dismiss())
                .create();
        activationDialog = dialog;
        activationField = field;
        dialog.setDismissDialogByButtons(false);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            validate.run();
            return true;
        });
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        });
        showDialog(dialog, d -> {
            AndroidUtilities.hideKeyboard(field);
            field.setText("");
            activationDialog = null;
            activationField = null;
        });
    }
}
