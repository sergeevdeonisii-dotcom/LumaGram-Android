package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;

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
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LumaDialogInput;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.RadioButton;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.GLIcon.LunagramLogoMesh;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.ColorUtils;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** Server-confirmed public subscription; the personal edition remains unrestricted. */
public final class LunagramSubscriptionActivity extends BaseFragment {
    private static final int PERIOD_ROW_START = 150;
    private static final int ROW_CRYPTO_BOT = 200;
    private static final int ROW_TON = 201;
    private static final int ROW_PAY = 202;
    private static final int ROW_STARS = 203;
    private static final int ROW_CRYPTO_OPEN = 204;
    private static final int ROW_CRYPTO_CHECK = 205;
    private static final int ROW_CRYPTO_ACTIVATE = 206;
    private static final int ROW_ACTIVATE_CODE = 300;
    private static final int ROW_CONNECT = 400;
    private static final int ROW_CHECK = 401;
    private static final int ROW_CANCEL_AUTH = 402;
    private static final int ROW_DISCONNECT = 403;
    private static final int ROW_OPEN_BOT = 404;
    private static final int ROW_SELECT_PERIOD = 451;
    private static final int ROW_TERM_DETAILS = 452;
    private static final int ROW_SELECT_PAYMENT = 453;
    private static final int FEATURE_ROW_START = 500;
    private static final int ACTIVATION_IDLE = 0;
    private static final int ACTIVATION_PREVIEW = 1;
    private static final int ACTIVATION_CONFIRM = 2;

    private final Plan selectedPlan = Plan.ULTRA;
    private Period selectedPeriod = Period.MONTH_1;
    private PaymentMethod selectedMethod = PaymentMethod.TELEGRAM_STARS;
    private UniversalRecyclerView listView;
    private TextInfoPrivacyCell connectionCell;
    private TextInfoPrivacyCell featuresCell;
    private TextInfoPrivacyCell termCell;
    private TextInfoPrivacyCell cryptoCell;
    private LinearLayout subscriptionHeader;
    private TextView currentPlanLabel;
    private TextView subscriptionTitle;
    private TextView subscriptionIntro;
    private GLIconTextureView subscriptionLogo;
    private View logoFallback;
    private Bitmap logoProbe;
    private boolean logoReady;
    private boolean logoFailed;
    private int logoProbeFrames;
    private LunagramSubscriptionClient client;
    private String openedPairUrl;
    private boolean authenticationLaunchRequested;
    private boolean statusRequested;
    private boolean statusLoading;
    private boolean cryptoRequested, cryptoLaunchRequested, activationIsCrypto;
    private boolean cryptoBotOpening;
    private int cryptoBotNavigation;
    private AlertDialog activationDialog;
    private EditTextBoldCursor activationField;
    private int activationOperation;
    private final Runnable termBoundary = () -> {
        if (client == null || isPaused || isFinished || !client.isAccountCurrent()) return;
        if (listView != null) listView.adapter.update(false);
        if (!client.isBusy() && client.isAuthenticated()) {
            statusRequested = true;
            statusLoading = true;
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
        updateLogoState();
        if (client == null) return;
        if (client.isTestAccount()) { client.dispose(); onSubscriptionChanged(); return; }
        if (!client.isAccountCurrent()) { client.dispose(); finishFragment(); return; }
        statusRequested = false;
        cryptoRequested = false;
        statusLoading = false;
        if (client.isPairing()) client.checkAuthentication();
        else if (client.isAuthenticated()) { statusRequested = true; statusLoading = true; client.refresh(); }
        else { statusRequested = false; statusLoading = true; client.restore(); }
        onSubscriptionChanged();
    }

    @Override
    public void onPause() {
        // BaseFragment marks this owner paused before dismiss/clearQuote callbacks can re-enter.
        super.onPause();
        statusLoading = false;
        updateLogoState();
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(termBoundary);
        activationOperation = ACTIVATION_IDLE;
        cryptoLaunchRequested = false;
        authenticationLaunchRequested = false;
        cryptoBotOpening = false;
        cryptoBotNavigation++;
        if (client != null) client.clearQuote();
        if (client != null) client.pause();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (subscriptionLogo != null) {
            subscriptionLogo.cancelIdleAnimation();
            subscriptionLogo.cancelAnimatons();
            subscriptionLogo.stopThread();
        }
        if (logoProbe != null) { logoProbe.recycle(); logoProbe = null; }
        cryptoBotOpening = false;
        cryptoBotNavigation++;
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(termBoundary);
        if (activationDialog != null) activationDialog.dismiss();
        if (client != null) client.dispose();
        client = null;
    }

    private void onSubscriptionChanged() {
        if (client == null || isFinished) return;
        if (!client.isBusy()) statusLoading = false;
        // Complete restore -> live status before rebuilding the header. Historical
        // receipt data remains untrusted, but must not flash as an error mid-check.
        if (!isPaused && client.isAuthenticated() && !client.isBusy() && !statusRequested) {
            statusRequested = true; statusLoading = true; client.refresh(); return;
        }
        if (listView != null) listView.adapter.update(false);
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (isPaused) return;
        scheduleTermBoundary();
        updateActivationDialog();
        if (client.isAuthenticated() && !client.isBusy() && !cryptoRequested
                && client.error() == LunagramSubscriptionClient.Error.NONE && activationDialog == null) {
            cryptoRequested = true; client.refreshCrypto(); return;
        }
        if (cryptoLaunchRequested && !client.isBusy()) {
            cryptoLaunchRequested = false;
            if (client.error() == LunagramSubscriptionClient.Error.NONE) openCryptoCheckout();
        }
        // Restoring an approved pairing must finish here, not send the user back
        // to the bot. Navigation belongs only to an explicit Connect action.
        if (authenticationLaunchRequested && !client.isBusy()) {
            authenticationLaunchRequested = false;
            String url = client.authenticationUrl();
            if (client.error() == LunagramSubscriptionClient.Error.NONE
                    && url != null && !url.equals(openedPairUrl) && getParentActivity() != null) {
                openedPairUrl = url;
                Browser.openUrl(getParentActivity(), url, false);
            }
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
        // This is the first row of its own rounded section, not a header's subtitle.
        termCell.setTopPadding(16);
        termCell.setBottomPadding(16);
        cryptoCell = new TextInfoPrivacyCell(context, resourceProvider);
        cryptoCell.setTopPadding(0);
        cryptoCell.setBottomPadding(12);
        subscriptionHeader = createSubscriptionHeader(context);
        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSections();
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        actionBar.setAdaptiveBackground(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateLogoState();
            }
        });
        return fragmentView = content;
    }

    private LinearLayout createSubscriptionHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        FrameLayout logoFrame = new FrameLayout(context);
        logoFallback = new LogoFallback(context);
        logoFallback.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        logoFrame.addView(logoFallback, LayoutHelper.createFrame(-1, -1));
        subscriptionLogo = new GLIconTextureView(context, GLIconRenderer.FRAGMENT_STYLE, Icon3D.TYPE_LUNAGRAM) {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                logoReady = false;
                logoFailed = false;
                logoProbeFrames = 0;
                logoFallback.setVisibility(View.VISIBLE);
                // Icon3D's first draw is intentionally alpha=0. "whenReady"
                // only means submitted, not visible: never pause on that draw.
                super.onSurfaceTextureAvailable(surface, width, height);
                updateLogoState();
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                super.onSurfaceTextureUpdated(surface);
                if (surface != getSurfaceTexture() || isFinished || logoReady || logoFailed) return;
                verifyLogoFrame();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                logoReady = false;
                logoFallback.setVisibility(View.VISIBLE);
                cancelIdleAnimation();
                cancelAnimatons();
                return super.onSurfaceTextureDestroyed(surface);
            }
            @Override public void scheduleIdleAnimation(long time) {
                if (isFinished || LunagramSubscriptionActivity.this.isPaused || !logoMotionAllowed()) {
                    cancelIdleAnimation();
                    return;
                }
                super.scheduleIdleAnimation(time);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                return logoReady && !LunagramSubscriptionActivity.this.isPaused && !isFinished
                        && logoMotionAllowed() && super.onTouchEvent(event);
            }
        };
        subscriptionLogo.setContentDescription(getString(R.string.LunagramSubscriptionRotateLogo));
        subscriptionLogo.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateLogoState());
        logoReady = false;
        logoFrame.addView(subscriptionLogo, LayoutHelper.createFrame(-1, -1));
        logoFallback.bringToFront();
        header.addView(logoFrame, new LinearLayout.LayoutParams(AndroidUtilities.dp(168), AndroidUtilities.dp(168)));
        subscriptionTitle = new TextView(context);
        subscriptionTitle.setText(getString(R.string.LunagramSubscriptionUltraTitle));
        subscriptionTitle.setTextSize(24);
        subscriptionTitle.setTypeface(AndroidUtilities.bold());
        subscriptionTitle.setGravity(Gravity.CENTER);
        header.addView(subscriptionTitle, new LinearLayout.LayoutParams(-1, -2));
        HeaderStatusCell statusCell = new HeaderStatusCell(context,
                getString(R.string.LunagramSubscriptionCheckingStatus),
                getString(R.string.LunagramSubscriptionUltraActive),
                getString(LumaBuildPolicy.isPersonalEdition()
                        ? R.string.LunagramSubscriptionPersonalTier : R.string.LunagramSubscriptionFreeTier));
        currentPlanLabel = statusCell.label;
        currentPlanLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.topMargin = AndroidUtilities.dp(8);
        header.addView(statusCell, labelParams);
        subscriptionIntro = new TextView(context);
        subscriptionIntro.setText(getString(LumaBuildPolicy.isPersonalEdition()
                ? R.string.LunagramSubscriptionPersonalAccess : R.string.LunagramSubscriptionUltraIntro));
        subscriptionIntro.setTextSize(15);
        subscriptionIntro.setGravity(Gravity.CENTER);
        subscriptionIntro.setLineSpacing(AndroidUtilities.dp(2), 1f);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(-1, -2);
        introParams.topMargin = AndroidUtilities.dp(10);
        header.addView(subscriptionIntro, introParams);
        updateCurrentPlanLabelColor();
        return header;
    }

    /** Reserve the natural wrapped height of compact states, never a fixed or capped text height. */
    public static final class HeaderStatusCell extends FrameLayout {
        final TextView label;

        HeaderStatusCell(Context context, String... compactStates) {
            super(context);
            for (String state : compactStates) {
                TextView reservation = createLabel(context);
                reservation.setText(state);
                reservation.setVisibility(View.INVISIBLE);
                reservation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(reservation, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
            }
            label = createLabel(context);
            addView(label, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        private static TextView createLabel(Context context) {
            TextView label = new TextView(context);
            label.setTextSize(15);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(false);
            return label;
        }
    }

    private void verifyLogoFrame() {
        // Bounded 24x24 readback only during initial reveal, not every live frame.
        // Keep the exact same static mark if EGL/shader setup fails or stays blank.
        if (++logoProbeFrames % 4 != 0) return;
        try {
            if (logoProbe == null) logoProbe = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Bitmap frame = subscriptionLogo.getBitmap(logoProbe);
            if (hasVisibleLogoFrame(frame)) {
                logoReady = true;
                logoFallback.setVisibility(View.INVISIBLE);
            }
        } catch (RuntimeException ignored) {
            logoFailed = true;
        }
        if (logoProbeFrames >= 32 && !logoReady) logoFailed = true;
        updateLogoState();
    }

    private static boolean hasVisibleLogoFrame(Bitmap frame) {
        if (frame == null) return false;
        int opaque = 0, white = 0, brand = 0;
        for (int y = 5; y < 19; y++) for (int x = 5; x < 19; x++) {
            int pixel = frame.getPixel(x, y);
            if (Color.alpha(pixel) < 240) continue;
            opaque++;
            int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
            if (r > 180 && g > 180 && b > 180) white++;
            if (b > r + 24 && b > g + 8) brand++;
        }
        return opaque >= 64 && white >= 8 && brand >= 12;
    }

    /** Exact front-facing geometry of the same 3D mark; never an old L/stock icon. */
    private static final class LogoFallback extends View {
        private final Path[] parts = new Path[LunagramLogoMesh.PART_COUNT];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Shader gradient = new LinearGradient(-24, 24, 24, -24,
                0xff38abf5, 0xff7557fc, Shader.TileMode.CLAMP);
        LogoFallback(Context context) {
            super(context);
            for (int part = 0; part < parts.length; part++) {
                LunagramLogoMesh.Data data = LunagramLogoMesh.create(part);
                Path path = parts[part] = new Path();
                for (int i = 0; i < data.positions.length; i += 9) {
                    if (data.normals[i + 2] < .999f) continue;
                    path.moveTo(data.positions[i], data.positions[i + 1]);
                    path.lineTo(data.positions[i + 3], data.positions[i + 4]);
                    path.lineTo(data.positions[i + 6], data.positions[i + 7]);
                    path.close();
                }
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.scale(getWidth() / 100f, -getHeight() / 100f);
            for (int part = 0; part < parts.length; part++) {
                paint.setColor(0xfffafbff);
                paint.setShader(part == LunagramLogoMesh.DISC ? gradient : null);
                canvas.drawPath(parts[part], paint);
            }
            canvas.restore();
        }
    }

    /** PremiumFeatureCell's native icon/text/chevron pattern, with wrapping sp text and RTL. */
    public static final class FeatureCell extends FrameLayout implements Theme.Colorable {
        private final TextView title, description;
        private final ImageView icon, chevron;
        private final Theme.ResourcesProvider provider;
        private final int textStart;
        private boolean divider;
        FeatureCell(Context context, Theme.ResourcesProvider provider, boolean choice) {
            super(context);
            this.provider = provider;
            // This app's LinearLayoutManager defaults to WRAP_CONTENT in both axes.
            // Section rows must share the list width, not their text's intrinsic width.
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            setMinimumHeight(AndroidUtilities.dp(choice ? 64 : 76));
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(16);
            if (!choice) title.setTypeface(AndroidUtilities.bold());
            title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            description = new TextView(context);
            description.setTextSize(14);
            description.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            description.setLineSpacing(AndroidUtilities.dp(2), 1f);
            text.addView(title, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(-1, -2);
            body.topMargin = AndroidUtilities.dp(3);
            text.addView(description, body);
            int start = textStart = choice ? 18 : 62;
            addView(text, LayoutHelper.createFrame(-1, -2, Gravity.TOP,
                    LocaleController.isRTL ? 42 : start, 12, LocaleController.isRTL ? start : 42, 12));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5));
            icon.setColorFilter(Color.WHITE);
            icon.setVisibility(choice ? View.GONE : View.VISIBLE);
            addView(icon, LayoutHelper.createFrame(32, 32,
                    Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 16, 13, 16, 0));
            chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.msg_arrowright);
            chevron.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (LocaleController.isRTL) chevron.setRotation(180);
            addView(chevron, LayoutHelper.createFrame(20, 20,
                    Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 12, 0, 12, 0));
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            description.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            updateColors();
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        @Override public void updateColors() {
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider));
            description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, provider));
            chevron.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, provider));
        }
        private void bind(UItem item, boolean divider) {
            title.setText(item.text);
            description.setText(item.subtext);
            icon.setImageResource(item.iconResId);
            icon.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), item.intValue));
            this.divider = divider;
            setContentDescription(item.text + ". " + item.subtext);
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (divider) canvas.drawRect(LocaleController.isRTL ? 0 : AndroidUtilities.dp(textStart), getHeight() - 1,
                    getWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(textStart) : 0), getHeight(), Theme.dividerPaint);
        }
        public static final class Factory extends UItem.UItemFactory<FeatureCell> {
            static { setup(new Factory()); }
            @Override public FeatureCell createView(Context context, RecyclerListView listView, int account,
                                                     int classGuid, Theme.ResourcesProvider provider) {
                return new FeatureCell(context, provider, false);
            }
            @Override public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                                           UniversalRecyclerView listView) {
                ((FeatureCell) view).bind(item, divider);
            }
            public static UItem of(int id, int color, int icon, String title, String info) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id; item.intValue = color; item.iconResId = icon;
                item.text = title; item.subtext = info;
                return item;
            }
        }
        /** The native detail-row pattern, but without single-line price truncation. */
        public static final class ChoiceFactory extends UItem.UItemFactory<FeatureCell> {
            static { setup(new ChoiceFactory()); }
            @Override public FeatureCell createView(Context context, RecyclerListView listView, int account,
                                                     int classGuid, Theme.ResourcesProvider provider) {
                return new FeatureCell(context, provider, true);
            }
            @Override public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                                           UniversalRecyclerView listView) {
                ((FeatureCell) view).bind(item, divider);
            }
            public static UItem of(int id, String title, String value) {
                UItem item = UItem.ofFactory(ChoiceFactory.class);
                item.id = id; item.text = title; item.subtext = value;
                return item;
            }
        }
    }

    /** A content-height native radio row: term first, full total price beneath it. */
    public static final class PeriodChoiceCell extends FrameLayout {
        private final RadioButton radioButton;

        PeriodChoiceCell(Context context, Theme.ResourcesProvider provider, String title, String detail, boolean checked) {
            super(context);
            setMinimumHeight(AndroidUtilities.dp(64));
            setFocusable(true);
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, provider), 2));
            LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, provider));
            titleView.setText(title);
            titleView.setSingleLine(false);
            titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            TextView detailView = new TextView(context);
            detailView.setTextSize(14);
            detailView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, provider));
            detailView.setText(detail);
            detailView.setSingleLine(false);
            detailView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            text.addView(titleView, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            detailParams.topMargin = AndroidUtilities.dp(3);
            text.addView(detailView, detailParams);
            addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP,
                    LocaleController.isRTL ? 21 : 54, 12, LocaleController.isRTL ? 54 : 21, 12));
            radioButton = new RadioButton(context);
            radioButton.setSize(AndroidUtilities.dp(20));
            radioButton.setColor(Theme.getColor(Theme.key_dialogRadioBackground, provider),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, provider));
            radioButton.setChecked(checked, false);
            addView(radioButton, LayoutHelper.createFrame(22, 22,
                    Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 20, 14, 20, 0));
            titleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            detailView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            radioButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setContentDescription(title + ". " + detail);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(radioButton.isChecked());
        }
    }

    private void showPeriodDialog() {
        if (getParentActivity() == null) return;
        LinearLayout options = new LinearLayout(getParentActivity());
        options.setOrientation(LinearLayout.VERTICAL);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        for (Period period : LunagramSubscriptionCatalog.PERIODS) {
            PeriodChoiceCell cell = new PeriodChoiceCell(getParentActivity(), resourceProvider,
                    periodTitle(period), periodPrice(selectedPlan, period), period == selectedPeriod);
            options.addView(cell, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            cell.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                if (client == null || !client.isAccountCurrent() || isPaused || isFinished) return;
                selectedPeriod = period;
                listView.adapter.update(false);
            });
        }
        // AlertDialog supplies its own height-bounded ScrollView for this custom content.
        showDialog(builder.setTitle(getString(R.string.LunagramSubscriptionPeriod)).setView(options)
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }

    private boolean logoMotionAllowed() {
        if (!SharedConfig.animationsEnabled() || AndroidUtilities.getAnimatorDurationScale() <= 0) return false;
        Context context = getContext();
        if (context == null) return false;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || !power.isPowerSaveMode();
    }

    private void updateLogoState() {
        if (subscriptionLogo == null) return;
        boolean visible = !isPaused && !isFinished && !logoFailed && subscriptionLogo.getLocalVisibleRect(new Rect());
        boolean motion = visible && logoReady && logoMotionAllowed();
        // Reduced motion still gets the first real 3D frame, then keeps its texture static.
        subscriptionLogo.setPaused(!visible || (!motion && logoReady));
        if (!motion) {
            subscriptionLogo.cancelIdleAnimation();
            subscriptionLogo.cancelAnimatons();
        } else {
            subscriptionLogo.scheduleIdleAnimation(2000);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (currentPlanLabel != null) {
            currentPlanLabel.setText(currentPlanText());
            updateCurrentPlanLabelColor();
        }
        // Shadow/custom is the adapter's transparent non-section header type.
        items.add(UItem.asCustomShadow(subscriptionHeader));
        LunagramFeatureCatalog.Tier tier = LunagramFeatureCatalog.tierFromId(selectedPlan.id);
        for (Feature feature : Feature.values()) {
            if (!LunagramFeatureCatalog.includes(tier, feature) || LunagramFeatureCatalog.isBaselineFree(feature)
                    || featureResource(feature) == 0) continue;
            items.add(FeatureCell.Factory.of(FEATURE_ROW_START + feature.ordinal(), featureColor(feature),
                    featureIcon(feature), getString(featureResource(feature)), getString(featureSummaryResource(feature))));
        }
        items.add(UItem.asShadow(null));
        items.add(FeatureCell.ChoiceFactory.of(ROW_SELECT_PERIOD, getString(R.string.LunagramSubscriptionPeriod),
                totalPrice(selectedPlan, selectedPeriod)));
        items.add(FeatureCell.ChoiceFactory.of(ROW_SELECT_PAYMENT, getString(R.string.LunagramSubscriptionPaymentMethod),
                getString(selectedMethod == PaymentMethod.CRYPTO_BOT_USDT
                        ? R.string.LunagramSubscriptionCryptoBot : R.string.LunagramSubscriptionStars)));
        if (selectedMethod == PaymentMethod.CRYPTO_BOT_USDT) {
            if (client != null && !client.isAuthenticated()) {
                items.add(UItem.asButton(ROW_CONNECT, getString(R.string.LunagramSubscriptionConnect))
                        .setEnabled(client.isAccountCurrent() && !client.isBusy() && !client.isTestAccount()));
            }
            fillCryptoItems(items);
        } else {
            items.add(UItem.asButton(ROW_PAY, getString(currentPlanId() != null
                            ? R.string.LunagramSubscriptionRenewUltra : R.string.LunagramSubscriptionBuyInBot))
                    .setEnabled(selectedMethod == PaymentMethod.TELEGRAM_STARS && client != null
                            && client.isAccountCurrent() && !client.isTestAccount()));
        }
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionRenewalInfo)));
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionAccount)));
        connectionCell.setText(connectionBlockText());
        items.add(UItem.asCustom(connectionCell));
        items.add(UItem.asButton(ROW_OPEN_BOT, getString(R.string.LunagramSubscriptionOpenBot))
                .setEnabled(client != null && client.isAccountCurrent() && !client.isTestAccount()));
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
        items.add(UItem.asHeader(getString(R.string.LunagramSubscriptionCodes)));
        items.add(UItem.asButton(ROW_ACTIVATE_CODE, getString(R.string.LunagramSubscriptionActivateCode))
                .setEnabled(client != null && client.isAuthenticated() && !client.isBusy()));
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionConnectToActivate)));
        termCell.setText(compactSubscriptionFooter());
        items.add(UItem.asCustom(termCell));
        items.add(UItem.asButton(ROW_TERM_DETAILS, getString(R.string.LunagramSubscriptionTermStatus)));
        items.add(UItem.asShadow(null));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (client == null || !client.isAccountCurrent() || isPaused || isFinished) return;
        if (item.id == ROW_SELECT_PAYMENT) {
            String[] options = {getString(R.string.LunagramSubscriptionStars), getString(R.string.LunagramSubscriptionCryptoBot)};
            if (getParentActivity() != null) showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), options,
                    getString(R.string.LunagramSubscriptionPaymentMethod), selectedMethod == PaymentMethod.CRYPTO_BOT_USDT ? 1 : 0,
                    (dialog, index) -> {
                        if (client == null || !client.isAccountCurrent() || isPaused || isFinished || index < 0 || index > 1) return;
                        selectedMethod = index == 1 ? PaymentMethod.CRYPTO_BOT_USDT : PaymentMethod.TELEGRAM_STARS;
                        listView.adapter.update(false);
                    }));
        } else if (item.id == ROW_SELECT_PERIOD) {
            showPeriodDialog();
        } else if (item.id == ROW_TERM_DETAILS) {
            if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                    .setTitle(getString(R.string.LunagramSubscriptionTermStatus)).setMessage(subscriptionFooter())
                    .setPositiveButton(getString(R.string.OK), null).create());
        } else if (item.id >= FEATURE_ROW_START && item.id < FEATURE_ROW_START + Feature.values().length) {
            Feature feature = Feature.values()[item.id - FEATURE_ROW_START];
            if (!LunagramFeatureCatalog.includes(LunagramFeatureCatalog.tierFromId(selectedPlan.id), feature)
                    || LunagramFeatureCatalog.isBaselineFree(feature) || featureResource(feature) == 0) return;
            if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                    .setTitle(getString(featureResource(feature))).setMessage(getString(featureInfoResource(feature)))
                    .setPositiveButton(getString(R.string.OK), null).create());
        } else if (item.id >= PERIOD_ROW_START && item.id < PERIOD_ROW_START + LunagramSubscriptionCatalog.PERIODS.size()) {
            selectedPeriod = LunagramSubscriptionCatalog.PERIODS.get(item.id - PERIOD_ROW_START);
            listView.adapter.update(false);
        } else if (item.id == ROW_STARS || item.id == ROW_CRYPTO_BOT || item.id == ROW_TON) {
            selectedMethod = item.id == ROW_STARS ? PaymentMethod.TELEGRAM_STARS
                    : item.id == ROW_TON ? PaymentMethod.TON : PaymentMethod.CRYPTO_BOT_USDT;
            listView.adapter.update(false);
        } else if (item.id == ROW_OPEN_BOT) {
            openSubscriptionBot();
        } else if (item.id == ROW_CONNECT && client != null) {
            openedPairUrl = null; statusRequested = false;
            authenticationLaunchRequested = true; client.startAuthentication();
        } else if (item.id == ROW_CHECK && client != null) {
            if (client.isPairing()) client.checkAuthentication();
            else { statusRequested = true; statusLoading = true; cryptoRequested = false; client.refresh(); }
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
            if (!client.isTestAccount() && getParentActivity() != null) Browser.openUrl(getParentActivity(), LunagramSubscriptionClient.purchaseUrl(selectedPlan, selectedPeriod), false);
        } else if (item.id == ROW_PAY && selectedMethod == PaymentMethod.CRYPTO_BOT_USDT) {
            showCryptoTerms();
        } else if (item.id == ROW_CRYPTO_OPEN) {
            openCryptoCheckout();
        } else if (item.id == ROW_CRYPTO_CHECK && !client.isBusy() && client.isAuthenticated()) {
            cryptoRequested = true; client.checkCryptoOrder();
        } else if (item.id == ROW_CRYPTO_ACTIVATE) {
            showCryptoActivationDialog();
        } else if (item.id == ROW_ACTIVATE_CODE && client != null && client.isAuthenticated()) {
            showActivationDialog();
        }
    }

    private void fillCryptoItems(ArrayList<UItem> items) {
        LunagramSubscriptionClient.CryptoState state = client == null ? null : client.cryptoState();
        LunagramSubscriptionClient.CryptoOrder order = state == null ? null : state.order;
        boolean ready = client != null && client.isAuthenticated() && !client.isBusy() && client.isAccountCurrent();
        cryptoCell.setText(cryptoStatusText(state));
        items.add(UItem.asCustom(cryptoCell));
        if (client != null && client.isCryptoRecoveryRequired()) {
            items.add(UItem.asButton(ROW_CRYPTO_CHECK, getString(R.string.LunagramSubscriptionCryptoCheck)).setEnabled(ready));
        } else if (order != null && order.outstanding()) {
            if ("pending".equals(order.status) && order.checkoutUrl != null) {
                items.add(UItem.asButton(ROW_CRYPTO_OPEN, getString(R.string.LunagramSubscriptionCryptoOpen)).setEnabled(ready));
            }
            if ("paid".equals(order.status)) {
                items.add(UItem.asButton(ROW_CRYPTO_ACTIVATE, getString(R.string.LunagramSubscriptionCryptoActivate)).setEnabled(ready));
            } else {
                items.add(UItem.asButton(ROW_CRYPTO_CHECK, getString(R.string.LunagramSubscriptionCryptoCheck)).setEnabled(ready));
            }
        } else {
            items.add(UItem.asButton(ROW_PAY, getString(R.string.LunagramSubscriptionCryptoPay))
                    .setEnabled(ready && state != null && state.available));
            if (state == null || !state.available) {
                items.add(UItem.asButton(ROW_CRYPTO_CHECK, getString(R.string.LunagramSubscriptionRefresh)).setEnabled(ready));
            }
        }
        items.add(UItem.asShadow(getString(R.string.LunagramSubscriptionCryptoRenewal)));
    }

    private String cryptoStatusText(LunagramSubscriptionClient.CryptoState state) {
        if (client == null || !client.isAuthenticated()) return getString(R.string.LunagramSubscriptionCryptoConnect);
        if (client.isCryptoRecoveryRequired()) return getString(R.string.LunagramSubscriptionCryptoRecovery);
        if (state == null) return getString(client.isBusy() ? R.string.LunagramSubscriptionLoading : R.string.LunagramSubscriptionCryptoNotChecked);
        LunagramSubscriptionClient.CryptoOrder order = state.order;
        if (order == null) return getString(state.available ? R.string.LunagramSubscriptionCryptoReady : R.string.LunagramSubscriptionUnavailableInfo);
        String amount = String.format(java.util.Locale.US, "%d.%02d", order.usdCents / 100, order.usdCents % 100);
        String text = LocaleController.formatString(R.string.LunagramSubscriptionCryptoOrder, planTitle(order.planId),
                periodTitle(Period.fromMonths(order.months)), amount);
        int status = "paid".equals(order.status) ? (order.activated ? R.string.LunagramSubscriptionCryptoActivated : R.string.LunagramSubscriptionCryptoPaid)
                : "expired".equals(order.status) ? R.string.LunagramSubscriptionCryptoExpired
                : "review_required".equals(order.status) ? R.string.LunagramSubscriptionCryptoReview
                : "creating".equals(order.status) ? R.string.LunagramSubscriptionCryptoCreating : R.string.LunagramSubscriptionCryptoPending;
        return text + "\n" + getString(status);
    }

    private void showCryptoTerms() {
        if (client == null || !client.isAuthenticated() || client.isBusy() || !client.isAccountCurrent()
                || client.isTestAccount() || isPaused || isFinished || getParentActivity() == null) return;
        final Plan plan = selectedPlan;
        final Period period = selectedPeriod;
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.LunagramSubscriptionCryptoPay))
                .setMessage(LocaleController.formatString(R.string.LunagramSubscriptionCryptoOrder, plan.title,
                        periodTitle(period), plan.totalUsdAmount(period)) + "\n\n" + getString(R.string.LunagramSubscriptionCryptoTerms))
                .setPositiveButton(getString(R.string.LunagramSubscriptionCryptoAccept), (d, which) -> {
                    if (client == null || !client.isAuthenticated() || client.isBusy() || !client.isAccountCurrent()
                            || client.isTestAccount() || isPaused || isFinished) return;
                    cryptoRequested = true; cryptoLaunchRequested = true;
                    client.createCryptoOrder(plan, period);
                })
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }

    private void openCryptoCheckout() {
        if (client == null || !client.isAuthenticated() || !client.isAccountCurrent() || client.isBusy() || cryptoBotOpening
                || isPaused || isFinished || getParentActivity() == null) return;
        LunagramSubscriptionClient.CryptoState state = client.cryptoState();
        LunagramSubscriptionClient.CryptoOrder order = state == null ? null : state.order;
        if (order != null && "pending".equals(order.status) && LunagramSubscriptionClient.isCryptoCheckoutUrl(order.checkoutUrl)) {
            final LunagramSubscriptionClient checkoutClient = client;
            final int checkoutAccount = currentAccount;
            final int navigation = ++cryptoBotNavigation;
            final String orderId = order.orderId;
            final String checkoutUrl = order.checkoutUrl;
            cryptoBotOpening = true;
            // The general deep-link handler does not recheck the account after resolving
            // a username. Keep this invoice tied to its owner and this foreground screen.
            getMessagesController().getUserNameResolver().resolve("CryptoBot", null, true, peerId -> {
                if (navigation != cryptoBotNavigation || !cryptoBotOpening) return;
                cryptoBotOpening = false;
                if (client != checkoutClient || !client.isAuthenticated() || !client.isAccountCurrent()
                        || client.isBusy() || currentAccount != checkoutAccount || isPaused || isFinished
                        || getParentActivity() == null) return;
                LunagramSubscriptionClient.CryptoState currentState = client.cryptoState();
                LunagramSubscriptionClient.CryptoOrder currentOrder = currentState == null ? null : currentState.order;
                if (currentOrder == null || !"pending".equals(currentOrder.status)
                        || !orderId.equals(currentOrder.orderId) || !checkoutUrl.equals(currentOrder.checkoutUrl)) return;
                TLRPC.User bot = peerId == null || peerId <= 0 ? null : getMessagesController().getUser(peerId);
                if (bot == null || !bot.bot || bot.deleted || bot.id != peerId) {
                    showCryptoCheckoutError();
                    return;
                }
                Bundle args = new Bundle();
                args.putLong("user_id", bot.id);
                args.putString("botUser", checkoutUrl.substring("https://t.me/CryptoBot?start=".length()));
                if (!getMessagesController().checkCanOpenChat(args, this)) return;
                ChatActivity chat = new ChatActivity(args);
                chat.setCurrentAccount(checkoutAccount);
                if (!presentFragment(chat)) showCryptoCheckoutError();
            });
        }
    }

    private void showCryptoCheckoutError() {
        if (isPaused || isFinished || getParentActivity() == null) return;
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.LunagramSubscriptionCryptoOpen))
                .setMessage(getString(R.string.LunagramSubscriptionCryptoBrowserError))
                .setPositiveButton(getString(R.string.OK), null).create());
    }

    private void openSubscriptionBot() {
        if (client == null || !client.isAccountCurrent() || client.isTestAccount()
                || isPaused || isFinished || getParentActivity() == null) return;
        // Native mode 3 keeps this screen in the back stack and does not send /start.
        getMessagesController().openByUserName("LunagramAppPayBot", this, 3);
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

    private String periodPrice(Plan plan, Period period) {
        return LocaleController.formatString(R.string.LunagramSubscriptionPeriodPrice,
                plan.totalStars(period), plan.totalUsdAmount(period));
    }

    private String planTitle(String planId) {
        // Recognized historical invoices keep their exact amount/dates, but all
        // their paid features now belong to the single Ultra offer.
        for (Plan plan : Plan.values()) if (plan.id.equals(planId)) return Plan.ULTRA.title;
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
            case ROUND_VIDEO_DATE: return R.string.LunagramSubscriptionFeatureRoundDate;
            case ROUND_VIDEO_FILTERS: return R.string.LunagramSubscriptionFeatureRoundFilters;
            case ROUND_VIDEO_PORTRAIT: return R.string.LunagramSubscriptionFeatureRoundPortrait;
            case VOICE_STEREO: return R.string.LunagramSubscriptionFeatureVoiceStereo;
            case VOICE_FILTERS: return R.string.LunagramSubscriptionFeatureVoiceFilters;
            case SWIPE_ACTIONS: return R.string.LunagramSubscriptionFeatureSwipeActions;
            case CHANNEL_FEED: return R.string.LunagramSubscriptionFeatureFeed;
            case LUNA_PET: return R.string.LunagramSubscriptionFeaturePet;
            case INTERFACE_SOUNDS: return R.string.LunagramSubscriptionFeatureSounds;
            case CHAT_ATMOSPHERE: return R.string.LunagramSubscriptionFeatureAtmosphere;
            case SEND_ANIMATIONS: return R.string.LunagramSubscriptionFeatureSendAnimations;
            case STREAMER_MODE: return R.string.LunagramSubscriptionFeatureStreamer;
            case MESSAGE_REMINDERS: return R.string.LunagramSubscriptionFeatureReminders;
            case VIDEO_MEMES: return R.string.LunagramSubscriptionFeatureMemes;
            case TEXT_TOOLS: return R.string.LulPlanText;
            case READING_TOOLS: return R.string.LulPlanReading;
            case LINK_TOOLS: return R.string.LulPlanLinks;
            case LOCAL_WORKSPACE: return R.string.LulPlanWorkspace;
            case MEDIA_TOOLS: return R.string.LulPlanMedia;
            default: return 0;
        }
    }

    private int featureSummaryResource(Feature feature) {
        switch (feature) {
            case TEXT_TOOLS: return R.string.LulPlanTextShort;
            case READING_TOOLS: return R.string.LulPlanReadingShort;
            case LINK_TOOLS: return R.string.LulPlanLinksShort;
            case LOCAL_WORKSPACE: return R.string.LulPlanWorkspaceShort;
            case MEDIA_TOOLS: return R.string.LulPlanMediaShort;
            default: return featureInfoResource(feature);
        }
    }

    private int featureInfoResource(Feature feature) {
        switch (feature) {
            case ADVANCED_GLASS: return R.string.LunagramSubscriptionInfoGlass;
            case AUTOMATIC_MESSAGE_FORMATTING: return R.string.LunagramSubscriptionInfoFormatting;
            case TYPING_MOTION: return R.string.LunagramSubscriptionInfoTyping;
            case SEND_UNDO: return R.string.LunagramSubscriptionInfoUndo;
            case ROUND_VIDEO_CONTROLS: return R.string.LunagramSubscriptionInfoRound;
            case LOCAL_NUMBER: return R.string.LunagramSubscriptionInfoNumber;
            case LOCAL_LEVEL: return R.string.LunagramSubscriptionInfoLevel;
            case LOCAL_VERIFICATION: return R.string.LunagramSubscriptionInfoVerification;
            case LOCAL_NFT: return R.string.LunagramSubscriptionInfoNft;
            case CHAT_NOTES: return R.string.LunagramSubscriptionInfoNotes;
            case SETTINGS_PROFILES: return R.string.LunagramSubscriptionInfoProfiles;
            case SETTINGS_TRANSFER: return R.string.LunagramSubscriptionInfoTransfer;
            case GHOST: return R.string.LunagramSubscriptionInfoGhost;
            case RETAIN_DELETED: return R.string.LunagramSubscriptionInfoDeleted;
            case EDIT_RETENTION: return R.string.LunagramSubscriptionInfoEdits;
            case EMERGENCY: return R.string.LunagramSubscriptionInfoEmergency;
            case CHAT_VAULT: return R.string.LunagramSubscriptionInfoVault;
            case NOTIFICATION_JOURNAL: return R.string.LunagramSubscriptionInfoJournal;
            case CHAT_EXPORT: return R.string.LunagramSubscriptionInfoExport;
            case CHAT_PDF: return R.string.LunagramSubscriptionInfoPdf;
            case ROUND_VIDEO_DATE: return R.string.LunagramSubscriptionInfoRoundDate;
            case ROUND_VIDEO_FILTERS: return R.string.LunagramSubscriptionInfoRoundFilters;
            case ROUND_VIDEO_PORTRAIT: return R.string.LunagramSubscriptionInfoRoundPortrait;
            case VOICE_STEREO: return R.string.LunagramSubscriptionInfoVoiceStereo;
            case VOICE_FILTERS: return R.string.LunagramSubscriptionInfoVoiceFilters;
            case SWIPE_ACTIONS: return R.string.LunagramSubscriptionInfoSwipeActions;
            case CHANNEL_FEED: return R.string.LunagramSubscriptionInfoFeed;
            case LUNA_PET: return R.string.LunagramSubscriptionInfoPet;
            case INTERFACE_SOUNDS: return R.string.LunagramSubscriptionInfoSounds;
            case CHAT_ATMOSPHERE: return R.string.LunagramSubscriptionInfoAtmosphere;
            case SEND_ANIMATIONS: return R.string.LunagramSubscriptionInfoSendAnimations;
            case STREAMER_MODE: return R.string.LunagramSubscriptionInfoStreamer;
            case MESSAGE_REMINDERS: return R.string.LunagramSubscriptionInfoReminders;
            case VIDEO_MEMES: return R.string.LunagramSubscriptionInfoMemes;
            case TEXT_TOOLS: return R.string.LulPlanTextInfo;
            case READING_TOOLS: return R.string.LulPlanReadingInfo;
            case LINK_TOOLS: return R.string.LulPlanLinksInfo;
            case LOCAL_WORKSPACE: return R.string.LulPlanWorkspaceInfo;
            case MEDIA_TOOLS: return R.string.LulPlanMediaInfo;
            default: return R.string.LunagramSubscriptionInfoLocal;
        }
    }

    private int featureIcon(Feature feature) {
        switch (feature) {
            case ADVANCED_GLASS: return R.drawable.premium_colors;
            case AUTOMATIC_MESSAGE_FORMATTING: return R.drawable.msg_edit;
            case TYPING_MOTION: return R.drawable.menu_premium_effects;
            case SEND_UNDO: return R.drawable.msg_retry;
            case ROUND_VIDEO_CONTROLS: return R.drawable.msg_camera;
            case LOCAL_NUMBER: return R.drawable.msg_calls;
            case LOCAL_LEVEL: return R.drawable.premium_tags;
            case LOCAL_VERIFICATION: return R.drawable.msg_select;
            case LOCAL_NFT: return R.drawable.msg_gallery;
            case CHAT_NOTES: return R.drawable.msg_saved;
            case SETTINGS_PROFILES: return R.drawable.msg_settings;
            case SETTINGS_TRANSFER: return R.drawable.msg_share;
            case GHOST: return R.drawable.msg_secret;
            case RETAIN_DELETED: return R.drawable.msg_archive;
            case EDIT_RETENTION: return R.drawable.msg_recent;
            case EMERGENCY: return R.drawable.msg_warning;
            case CHAT_VAULT: return R.drawable.settings_privacy;
            case NOTIFICATION_JOURNAL: return R.drawable.msg_notifications;
            case CHAT_EXPORT: return R.drawable.msg_download;
            case CHAT_PDF: return R.drawable.msg_copy;
            case ROUND_VIDEO_DATE: return R.drawable.msg_calendar;
            case ROUND_VIDEO_FILTERS: return R.drawable.msg_photo_settings;
            case ROUND_VIDEO_PORTRAIT: return R.drawable.msg_photo_blur;
            case VOICE_STEREO: return R.drawable.msg_voice_headphones;
            case VOICE_FILTERS: return R.drawable.msg_voice_unmuted;
            case SWIPE_ACTIONS: return R.drawable.msg_reorder;
            case CHANNEL_FEED: return R.drawable.msg_folders_channels;
            case LUNA_PET: return R.drawable.msg_emoji_cat;
            case INTERFACE_SOUNDS: return R.drawable.msg_voice_speaker;
            case CHAT_ATMOSPHERE: return R.drawable.msg_palette;
            case SEND_ANIMATIONS: return R.drawable.msg_send;
            case STREAMER_MODE: return R.drawable.msg_stories_myhide;
            case MESSAGE_REMINDERS: return R.drawable.input_schedule;
            case VIDEO_MEMES: return R.drawable.msg_photo_text2;
            case TEXT_TOOLS: return R.drawable.msg_text_check;
            case READING_TOOLS: return R.drawable.msg_view_file;
            case LINK_TOOLS: return R.drawable.msg_link;
            case LOCAL_WORKSPACE: return R.drawable.msg_folders;
            case MEDIA_TOOLS: return R.drawable.msg_media;
            default: return R.drawable.msg_info;
        }
    }

    private int featureColor(Feature feature) {
        switch (feature.ordinal() % 5) {
            case 0: return 0xff617cff;
            case 1: return 0xff31b9d0;
            case 2: return 0xffb659ff;
            case 3: return 0xfff38b31;
            default: return 0xff55b86a;
        }
    }

    private String compactSubscriptionFooter() {
        LunagramSubscriptionClient.Subscription subscription = client == null ? null : client.subscription();
        if (subscription == null) return getString(LumaBuildPolicy.isPersonalEdition()
                ? R.string.LunagramSubscriptionPersonalTier : R.string.LunagramSubscriptionStatusUnknown);
        if (!client.isAuthenticated() || !client.subscriptionIsFresh()) {
            return getString(R.string.LunagramSubscriptionLastConfirmed);
        }
        long now = client.estimatedServerNowMs();
        if (subscription.legacy && now < subscription.legacyExpiresAtMs) {
            return LocaleController.formatString(R.string.LunagramSubscriptionLegacyUntil, formattedDate(subscription.legacyExpiresAtMs));
        }
        LunagramSubscriptionClient.Term term = subscription.current;
        if (term != null && term.startsAtMs <= now && now < term.expiresAtMs) {
            return LocaleController.formatString(R.string.LunagramSubscriptionActiveUntil,
                    planTitle(term.planId), formattedDate(term.expiresAtMs));
        }
        return getString(LumaBuildPolicy.isPersonalEdition()
                ? R.string.LunagramSubscriptionPersonalTier : R.string.LunagramSubscriptionFreeTier);
    }

    private String currentPlanText() {
        if (statusLoading && client != null && client.isBusy() && client.isAccountCurrent()
                && !client.isTestAccount() && !isPaused && !isFinished && !client.subscriptionIsFresh()) {
            return getString(R.string.LunagramSubscriptionCheckingStatus);
        }
        LunagramSubscriptionClient.Subscription subscription = client == null ? null : client.subscription();
        if (subscription == null) return getString(LumaBuildPolicy.isPersonalEdition()
                ? R.string.LunagramSubscriptionPersonalTier : R.string.LunagramSubscriptionStatusUnknown);
        if (!client.isAuthenticated() || !client.subscriptionIsFresh()) return getString(R.string.LunagramSubscriptionLastConfirmed);
        long now = client.estimatedServerNowMs();
        if (subscription.legacy && now < subscription.legacyExpiresAtMs) return getString(R.string.LunagramSubscriptionUltraActive);
        String planId = currentPlanId();
        if (planId != null) {
            return getString(R.string.LunagramSubscriptionUltraActive);
        }
        return getString(LumaBuildPolicy.isPersonalEdition()
                ? R.string.LunagramSubscriptionPersonalTier : R.string.LunagramSubscriptionFreeTier);
    }

    /** Display identity only: never use the plan selected for a future purchase. */
    private String currentPlanId() {
        LunagramSubscriptionClient.Subscription subscription = client == null ? null : client.subscription();
        if (subscription == null || !client.isAuthenticated() || !client.subscriptionIsFresh()) return null;
        long now = client.estimatedServerNowMs();
        if (subscription.legacy && now < subscription.legacyExpiresAtMs) return Plan.ULTRA.id;
        LunagramSubscriptionClient.Term term = subscription.current;
        if (term == null || term.startsAtMs > now || now >= term.expiresAtMs) return null;
        for (Plan plan : Plan.values()) if (plan.id.equals(term.planId)) return Plan.ULTRA.id;
        return null;
    }

    private void updateCurrentPlanLabelColor() {
        if (currentPlanLabel == null) return;
        if (subscriptionTitle != null) subscriptionTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        if (subscriptionIntro != null) subscriptionIntro.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        currentPlanLabel.setTextColor(currentPlanLabelColor(currentPlanId(),
                Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider)));
    }

    private static int currentPlanLabelColor(String planId, int background, int neutral) {
        int light, dark;
        switch (planId == null ? "" : planId) {
            case "based": case "standard": case "plus": case "pro":
            case "ultra": light = 0xff7541b4; dark = 0xffc4a2ff; break;
            default: light = dark = neutral; break;
        }
        // The hero is a transparent custom-shadow row over windowBackgroundGray.
        background |= 0xff000000;
        int color = ColorUtils.calculateContrast(light, background) >= ColorUtils.calculateContrast(dark, background) ? light : dark;
        if (ColorUtils.calculateContrast(color, background) >= 4.5) return color;
        int contrastTarget = ColorUtils.calculateLuminance(background) > 0.179 ? 0xff000000 : 0xffffffff;
        for (int step = 1; step <= 20; step++) {
            int adjusted = ColorUtils.blendARGB(color, contrastTarget, step / 20f);
            if (ColorUtils.calculateContrast(adjusted, background) >= 4.5) return adjusted;
        }
        return contrastTarget;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = super.getThemeDescriptions();
        ThemeDescription.ThemeDescriptionDelegate labelColors = this::updateCurrentPlanLabelColor;
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null,
                labelColors, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null,
                null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(null, 0, null, null, null,
                labelColors, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(null, 0, null, null, null,
                labelColors, Theme.key_windowBackgroundWhiteGrayText));
        return descriptions;
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
            case PLAN_RETIRED: return getString(R.string.LunagramSubscriptionPlanRetired);
            case CLIENT_UPDATE_REQUIRED: return getString(R.string.LunagramSubscriptionClientUpdateRequired);
            case LEGACY_TERM_ACTIVE: return getString(R.string.LunagramSubscriptionLegacyActive);
            case QUOTE_EXPIRED: return getString(R.string.LunagramSubscriptionQuoteExpired);
            case QUOTE_STALE: return getString(R.string.LunagramSubscriptionQuoteStale);
            case QUEUE_FULL: return getString(R.string.LunagramSubscriptionQueueFull);
            case CHECKOUT_PENDING: return getString(R.string.LunagramSubscriptionCryptoOutstanding);
            case PAYMENT_PENDING: return getString(R.string.LunagramSubscriptionCryptoPending);
            case PAYMENT_REVIEW: return getString(R.string.LunagramSubscriptionCryptoReview);
            case SERVER: return getString(R.string.LunagramSubscriptionServerError);
            default: return getString(R.string.LunagramSubscriptionNetworkError);
        }
    }

    private void showActivationDialog() {
        if (getParentActivity() == null) return;
        activationIsCrypto = false;
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

    private void showCryptoActivationDialog() {
        if (client == null || !client.isAuthenticated() || client.isBusy() || !client.isAccountCurrent()
                || isPaused || isFinished || getParentActivity() == null) return;
        client.clearQuote();
        activationIsCrypto = true;
        activationField = null;
        activationOperation = ACTIVATION_PREVIEW;
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.LunagramSubscriptionCryptoActivate))
                .setMessage(getString(R.string.LunagramSubscriptionLoading))
                .setPositiveButton(getString(R.string.LunagramSubscriptionConfirmCode), (d, which) -> {
                    if (client == null || client.isBusy() || !client.isAuthenticated() || !client.isAccountCurrent() || isPaused || isFinished) return;
                    if (client.quote() == null) { activationOperation = ACTIVATION_PREVIEW; client.previewCryptoOrder(); }
                    else { activationOperation = ACTIVATION_CONFIRM; client.confirmCode(); }
                })
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> d.dismiss()).create();
        activationDialog = dialog;
        dialog.setDismissDialogByButtons(false);
        showDialog(dialog, d -> {
            if (client != null) client.clearQuote();
            activationOperation = ACTIVATION_IDLE; activationDialog = null; activationIsCrypto = false;
        });
        client.previewCryptoOrder();
    }

    private void updateActivationDialog() {
        if (activationDialog == null || (!activationIsCrypto && activationField == null) || !activationDialog.isShowing() || client == null) return;
        if (activationOperation != ACTIVATION_IDLE && !client.isBusy()) {
            int completed = activationOperation;
            activationOperation = ACTIVATION_IDLE;
            if (completed == ACTIVATION_CONFIRM && client.error() == LunagramSubscriptionClient.Error.NONE) {
                if (activationField != null) activationField.setText("");
                if (activationIsCrypto) cryptoRequested = false;
                activationDialog.dismiss();
                return;
            }
        }
        LunagramSubscriptionClient.Quote quote = client.quote();
        boolean confirmation = quote != null;
        String message = activationIsCrypto && confirmation
                ? LocaleController.formatString(R.string.LunagramSubscriptionCryptoConfirmDetails,
                        planTitle(quote.planId), periodTitle(Period.fromMonths(quote.months)),
                        formattedDate(quote.startsAtMs), formattedDate(quote.expiresAtMs))
                : confirmation
                ? LocaleController.formatString(R.string.LunagramSubscriptionConfirmDetails,
                        planTitle(quote.planId), periodTitle(Period.fromMonths(quote.months)), quote.starsAmount,
                        formattedDate(quote.startsAtMs), formattedDate(quote.expiresAtMs))
                : getString(activationIsCrypto ? R.string.LunagramSubscriptionCryptoPreviewInfo : R.string.LunagramSubscriptionCodeInfo);
        if (client.error() != LunagramSubscriptionClient.Error.NONE) {
            message = errorText() + "\n\n" + message;
            if (!confirmation && activationField != null) activationField.setError(errorText());
        }
        activationDialog.setMessage(message);
        if (activationField != null) activationField.setVisibility(confirmation ? View.GONE : View.VISIBLE);
        if (confirmation && activationField != null) AndroidUtilities.hideKeyboard(activationField);
        View button = activationDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) ((TextView) button).setText(getString(confirmation
                ? R.string.LunagramSubscriptionConfirmCode : activationIsCrypto
                ? R.string.LunagramSubscriptionCryptoActivate : R.string.LunagramSubscriptionPreviewCode));
        if (button != null) button.setEnabled(!client.isBusy() && client.isAuthenticated() && client.isAccountCurrent());
    }
}
