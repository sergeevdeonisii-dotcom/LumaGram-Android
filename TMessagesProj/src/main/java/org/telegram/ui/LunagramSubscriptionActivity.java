package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramFeatureCatalog.Feature;
import org.telegram.messenger.LunagramSubscriptionCatalog;
import org.telegram.messenger.LunagramSubscriptionCatalog.PaymentMethod;
import org.telegram.messenger.LunagramSubscriptionCatalog.Plan;
import org.telegram.messenger.LunagramSubscriptionCatalog.Period;
import org.telegram.messenger.LunagramSubscriptionClient;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LumaDialogInput;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** Server-confirmed public subscription; the personal edition remains unrestricted. */
public final class LunagramSubscriptionActivity extends BaseFragment {
    private static final int PLAN_ROW_START = 100;
    private static final int PERIOD_ROW_START = 150;
    private static final int ROW_CRYPTO_BOT = 200;
    private static final int ROW_TON = 201;
    private static final int ROW_PAY = 202;
    private static final int ROW_STARS = 203;
    private static final int ROW_ACTIVATE_CODE = 300;
    private static final int ROW_CONNECT = 400;
    private static final int ROW_CHECK = 401;
    private static final int ROW_CANCEL_AUTH = 402;
    private static final int ROW_DISCONNECT = 403;
    private static final int ACTIVATION_IDLE = 0;
    private static final int ACTIVATION_PREVIEW = 1;
    private static final int ACTIVATION_CONFIRM = 2;

    private Plan selectedPlan = Plan.BASED;
    private Period selectedPeriod = Period.MONTH_1;
    private PaymentMethod selectedMethod = PaymentMethod.TELEGRAM_STARS;
    private UniversalRecyclerView listView;
    private TextInfoPrivacyCell connectionCell;
    private TextInfoPrivacyCell featuresCell;
    private TextInfoPrivacyCell termCell;
    private LunagramSubscriptionClient client;
    private String openedPairUrl;
    private boolean statusRequested;
    private AlertDialog activationDialog;
    private EditTextBoldCursor activationField;
    private int activationOperation;
    private final Runnable termBoundary = () -> {
        if (client == null || isPaused || isFinished || !client.isAccountCurrent()) return;
        if (listView != null) listView.adapter.update(false);
        if (!client.isBusy() && client.isAuthenticated()) {
            statusRequested = true;
            client.refresh();
        }
    };
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
        statusRequested = false;
        if (client.isPairing()) client.checkAuthentication();
        else if (client.isAuthenticated()) { statusRequested = true; client.refresh(); }
        else { statusRequested = false; client.restore(); }
        onSubscriptionChanged();
    }

    @Override
    public void onPause() {
        // BaseFragment marks this owner paused before dismiss/clearQuote callbacks can re-enter.
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(termBoundary);
        activationOperation = ACTIVATION_IDLE;
        if (client != null) client.clearQuote();
        if (client != null) client.pause();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(termBoundary);
        if (activationDialog != null) activationDialog.dismiss();
        if (client != null) client.dispose();
        client = null;
    }

    private void onSubscriptionChanged() {
        if (client == null || isFinished) return;
        if (listView != null) listView.adapter.update(false);
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (isPaused) return;
        scheduleTermBoundary();
        updateActivationDialog();
        // A restored cache is historical evidence, not a reason to skip a live refresh.
        if (client.isAuthenticated() && !client.isBusy() && !statusRequested) {
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
        connectionCell = new TextInfoPrivacyCell(context, resourceProvider);
        connectionCell.setTopPadding(0);
        connectionCell.setBottomPadding(12);
        featuresCell = new TextInfoPrivacyCell(context, resourceProvider);
        featuresCell.setTopPadding(0);
        featuresCell.setBottomPadding(12);
        termCell = new TextInfoPrivacyCell(context, resourceProvider);
        termCell.setTopPadding(0);
        termCell.setBottomPadding(12);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        return fragmentView = content;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asShadow(getString(LumaBuildPolicy.isPersonalEdition()
                ? R.string.LunagramSubscriptionPersonalAccess : R.string.LunagramSubscriptionAccessInfo)));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionAccount)));
        connectionCell.setText(connectionBlockText());
        items.add(UItem.asCustom(connectionCell));
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
        // A shadow ends the native rounded section only AFTER all account rows/actions.
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionPeriod)));
        for (int i = 0; i < LunagramSubscriptionCatalog.PERIODS.size(); i++) {
            Period period = LunagramSubscriptionCatalog.PERIODS.get(i);
            items.add(UItem.asRadio2(PERIOD_ROW_START + i, periodTitle(period),
                    totalPrice(selectedPlan, period)).setChecked(period == selectedPeriod));
        }
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionPlans)));
        for (int i = 0; i < LunagramSubscriptionCatalog.PLANS.size(); i++) {
            Plan plan = LunagramSubscriptionCatalog.PLANS.get(i);
            // The native two-line radio cell wraps the price and exposes checked state to TalkBack.
            items.add(UItem.asRadio2(PLAN_ROW_START + i, plan.title,
                    totalPrice(plan, selectedPeriod))
                    .setChecked(plan == selectedPlan));
        }
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionRenewalInfo)));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionPlannedFeatures)));
        featuresCell.setText(plannedFeaturesText(selectedPlan));
        items.add(UItem.asCustom(featuresCell));
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionDistributionPending)));
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
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionTermStatus)));
        termCell.setText(subscriptionFooter());
        items.add(UItem.asCustom(termCell));
        items.add(UItem.asShadow(null));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (client == null || !client.isAccountCurrent()) return;
        if (item.id >= PLAN_ROW_START && item.id < PLAN_ROW_START + LunagramSubscriptionCatalog.PLANS.size()) {
            selectedPlan = LunagramSubscriptionCatalog.PLANS.get(item.id - PLAN_ROW_START);
            listView.adapter.update(false);
        } else if (item.id >= PERIOD_ROW_START && item.id < PERIOD_ROW_START + LunagramSubscriptionCatalog.PERIODS.size()) {
            selectedPeriod = LunagramSubscriptionCatalog.PERIODS.get(item.id - PERIOD_ROW_START);
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
            if (getParentActivity() != null) Browser.openUrl(getParentActivity(), LunagramSubscriptionClient.purchaseUrl(selectedPlan, selectedPeriod), false);
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
        return account + "\n" + getString(R.string.LunagramSubscriptionConnectedReady);
    }

    private String connectionBlockText() {
        String text = connectionText();
        if (client != null && client.error() != LunagramSubscriptionClient.Error.NONE) text += "\n\n" + errorText();
        if (client != null && !client.isPersistentStorageAvailable()) text += "\n\n" + getString(R.string.LunagramSubscriptionEphemeral);
        return text;
    }

    private String periodTitle(Period period) {
        switch (period) {
            case MONTH_3: return getString(R.string.LunagramSubscriptionPeriod3);
            case MONTH_6: return getString(R.string.LunagramSubscriptionPeriod6);
            case MONTH_12: return getString(R.string.LunagramSubscriptionPeriod12);
            default: return getString(R.string.LunagramSubscriptionPeriod1);
        }
    }

    private String totalPrice(Plan plan, Period period) {
        return LocaleController.formatString(R.string.LunagramSubscriptionTotalPrice,
                plan.totalStars(period), plan.totalUsdAmount(period), periodTitle(period));
    }

    private String planTitle(String planId) {
        for (Plan plan : LunagramSubscriptionCatalog.PLANS) if (plan.id.equals(planId)) return plan.title;
        return getString(R.string.LunagramSubscriptionUnknownPlan);
    }

    private String formattedDate(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(timestamp));
    }

    private String plannedFeaturesText(Plan plan) {
        LunagramFeatureCatalog.Tier tier = LunagramFeatureCatalog.tierFromId(plan.id);
        StringBuilder text = new StringBuilder();
        for (Feature feature : Feature.values()) {
            if (!LunagramFeatureCatalog.includes(tier, feature) || LunagramFeatureCatalog.isBaselineFree(feature)) continue;
            int resource = featureResource(feature);
            if (resource == 0) continue;
            if (text.length() > 0) text.append(" · ");
            text.append(getString(resource));
        }
        return text.length() == 0 ? getString(R.string.LunagramSubscriptionUnknownPlan) : text.toString();
    }

    private int featureResource(Feature feature) {
        switch (feature) {
            case ADVANCED_GLASS: return R.string.LunagramSubscriptionFeatureGlass;
            case AUTOMATIC_MESSAGE_FORMATTING: return R.string.LunagramSubscriptionFeatureFormatting;
            case TYPING_MOTION: return R.string.LunagramSubscriptionFeatureTyping;
            case SEND_UNDO: return R.string.LunagramSubscriptionFeatureUndo;
            case ROUND_VIDEO_CONTROLS: return R.string.LunagramSubscriptionFeatureRound;
            case LOCAL_NUMBER: return R.string.LunagramSubscriptionFeatureNumber;
            case LOCAL_LEVEL: return R.string.LunagramSubscriptionFeatureLevel;
            case LOCAL_VERIFICATION: return R.string.LunagramSubscriptionFeatureVerification;
            case LOCAL_NFT: return R.string.LunagramSubscriptionFeatureNft;
            case CHAT_NOTES: return R.string.LunagramSubscriptionFeatureNotes;
            case SETTINGS_PROFILES: return R.string.LunagramSubscriptionFeatureProfiles;
            case SETTINGS_TRANSFER: return R.string.LunagramSubscriptionFeatureSettingsTransfer;
            case GHOST: return R.string.LunagramSubscriptionFeatureGhost;
            case RETAIN_DELETED: return R.string.LunagramSubscriptionFeatureDeleted;
            case EDIT_RETENTION: return R.string.LunagramSubscriptionFeatureEdits;
            case EMERGENCY: return R.string.LunagramSubscriptionFeatureEmergency;
            case CHAT_VAULT: return R.string.LunagramSubscriptionFeatureVault;
            case NOTIFICATION_JOURNAL: return R.string.LunagramSubscriptionFeatureJournal;
            case CHAT_EXPORT: return R.string.LunagramSubscriptionFeatureExport;
            case CHAT_PDF: return R.string.LunagramSubscriptionFeaturePdf;
            default: return 0;
        }
    }

    private String subscriptionFooter() {
        LunagramSubscriptionClient.Subscription subscription = client == null ? null : client.subscription();
        if (subscription == null) return getString(R.string.LunagramSubscriptionStatusUnknown);
        boolean fresh = client.isAuthenticated() && client.subscriptionIsFresh();
        StringBuilder text = new StringBuilder();
        if (!fresh) text.append(getString(R.string.LunagramSubscriptionLastConfirmed)).append("\n\n");
        long now = client.estimatedServerNowMs();
        if (subscription.legacy) {
            text.append(LocaleController.formatString(R.string.LunagramSubscriptionLegacyUntil,
                    formattedDate(subscription.legacyExpiresAtMs)));
        } else if (subscription.current != null) {
            LunagramSubscriptionClient.Term term = subscription.current;
            int resource = fresh && term.startsAtMs <= now && now < term.expiresAtMs
                    ? R.string.LunagramSubscriptionCurrentTerm : R.string.LunagramSubscriptionRecordedTerm;
            text.append(LocaleController.formatString(resource, planTitle(term.planId),
                    periodTitle(Period.fromMonths(term.months)), formattedDate(term.startsAtMs), formattedDate(term.expiresAtMs)));
        } else {
            text.append(getString(fresh ? R.string.LunagramSubscriptionInactive : R.string.LunagramSubscriptionRecordedNoTerm));
        }
        int shown = Math.min(3, subscription.queued.size());
        for (int i = 0; i < shown; i++) {
            LunagramSubscriptionClient.Term term = subscription.queued.get(i);
            int resource = fresh && now >= term.startsAtMs
                    ? R.string.LunagramSubscriptionTermNeedsRefresh : R.string.LunagramSubscriptionQueuedTerm;
            text.append("\n\n").append(LocaleController.formatString(resource,
                    planTitle(term.planId), periodTitle(Period.fromMonths(term.months)),
                    formattedDate(term.startsAtMs), formattedDate(term.expiresAtMs)));
        }
        if (subscription.queued.size() > shown) text.append("\n").append(LocaleController.formatString(
                R.string.LunagramSubscriptionMoreTerms, subscription.queued.size() - shown));
        if (subscription.paidUntilMs > 0) text.append("\n\n").append(LocaleController.formatString(
                R.string.LunagramSubscriptionPaidUntil, formattedDate(subscription.paidUntilMs)));
        text.append("\n").append(LocaleController.formatString(R.string.LunagramSubscriptionCheckedAt,
                formattedDate(subscription.serverNowMs)));
        return text.toString();
    }

    private void scheduleTermBoundary() {
        AndroidUtilities.cancelRunOnUIThread(termBoundary);
        if (client == null || isPaused || isFinished || !client.isAuthenticated() || !client.subscriptionIsFresh()) return;
        LunagramSubscriptionClient.Subscription subscription = client.subscription();
        long now = client.estimatedServerNowMs();
        if (subscription == null || now <= 0) return;
        long next = Long.MAX_VALUE;
        if (subscription.current != null && subscription.current.expiresAtMs > now) next = subscription.current.expiresAtMs;
        if (subscription.legacy && subscription.legacyExpiresAtMs > now) next = Math.min(next, subscription.legacyExpiresAtMs);
        for (LunagramSubscriptionClient.Term term : subscription.queued) {
            if (term.startsAtMs > now) next = Math.min(next, term.startsAtMs);
        }
        if (next != Long.MAX_VALUE) AndroidUtilities.runOnUIThread(termBoundary, next - now + 50);
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
            case UPGRADE_REQUIRED: return getString(R.string.LunagramSubscriptionUpgradeRequired);
            case LEGACY_TERM_ACTIVE: return getString(R.string.LunagramSubscriptionLegacyActive);
            case QUOTE_EXPIRED: return getString(R.string.LunagramSubscriptionQuoteExpired);
            case QUOTE_STALE: return getString(R.string.LunagramSubscriptionQuoteStale);
            case QUEUE_FULL: return getString(R.string.LunagramSubscriptionQueueFull);
            default: return getString(R.string.LunagramSubscriptionNetworkError);
        }
    }

    private void showActivationDialog() {
        if (getParentActivity() == null) return;
        client.clearQuote();
        activationOperation = ACTIVATION_IDLE;
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
            if (client == null || isPaused || isFinished || client.isBusy() || !client.isAuthenticated() || !client.isAccountCurrent()) return;
            LunagramSubscriptionClient.Quote quote = client.quote();
            if (quote != null) {
                // The client owns quote expiry and idempotent retries after a lost reply.
                activationOperation = ACTIVATION_CONFIRM;
                client.confirmCode();
                return;
            }
            String code = LunagramSubscriptionCatalog.normalizeActivationCode(field.getText());
            if (code == null) {
                field.setError(getString(R.string.LunagramSubscriptionCodeInvalid));
                return;
            }
            field.setError(null);
            activationOperation = ACTIVATION_PREVIEW;
            client.previewCode(code);
        };
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.LunagramSubscriptionActivateCode))
                .setMessage(getString(R.string.LunagramSubscriptionCodeInfo))
                .setView(LumaDialogInput.wrap(field))
                .setPositiveButton(getString(R.string.LunagramSubscriptionPreviewCode), (d, which) -> validate.run())
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> d.dismiss())
                .create();
        activationDialog = dialog;
        activationField = field;
        dialog.setDismissDialogByButtons(false);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            // An IME action can check a code, but never consume it in place of explicit confirmation.
            if (client != null && client.quote() != null) return true;
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
            if (client != null) client.clearQuote();
            activationOperation = ACTIVATION_IDLE;
            activationDialog = null;
            activationField = null;
        });
    }

    private void updateActivationDialog() {
        if (activationDialog == null || activationField == null || !activationDialog.isShowing() || client == null) return;
        if (activationOperation != ACTIVATION_IDLE && !client.isBusy()) {
            int completed = activationOperation;
            activationOperation = ACTIVATION_IDLE;
            if (completed == ACTIVATION_CONFIRM && client.error() == LunagramSubscriptionClient.Error.NONE) {
                activationField.setText("");
                activationDialog.dismiss();
                return;
            }
        }
        LunagramSubscriptionClient.Quote quote = client.quote();
        boolean confirmation = quote != null;
        String message = confirmation
                ? LocaleController.formatString(R.string.LunagramSubscriptionConfirmDetails,
                        planTitle(quote.planId), periodTitle(Period.fromMonths(quote.months)), quote.starsAmount,
                        formattedDate(quote.startsAtMs), formattedDate(quote.expiresAtMs))
                : getString(R.string.LunagramSubscriptionCodeInfo);
        if (client.error() != LunagramSubscriptionClient.Error.NONE) {
            message = errorText() + "\n\n" + message;
            if (!confirmation) activationField.setError(errorText());
        }
        activationDialog.setMessage(message);
        activationField.setVisibility(confirmation ? View.GONE : View.VISIBLE);
        if (confirmation) AndroidUtilities.hideKeyboard(activationField);
        View button = activationDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) ((TextView) button).setText(getString(confirmation
                ? R.string.LunagramSubscriptionConfirmCode : R.string.LunagramSubscriptionPreviewCode));
        if (button != null) button.setEnabled(!client.isBusy() && client.isAuthenticated() && client.isAccountCurrent());
    }
}
