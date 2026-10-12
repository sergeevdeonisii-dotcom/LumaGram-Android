package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LumaAnonymousNumber;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_fragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;


public class FragmentUsernameBottomSheet {

    public static final int TYPE_USERNAME = 0;
    public static final int TYPE_PHONE = 1;

    public static void open(
        Context context,
        int type,
        String name,
        TLObject owner,
        TL_fragment.TL_collectibleInfo info,
        Theme.ResourcesProvider resourcesProvider
    ) {
        open(context, type, name, owner, info, resourcesProvider, false);
    }

    private static void open(Context context, int type, String name, TLObject owner,
                             TL_fragment.TL_collectibleInfo info, Theme.ResourcesProvider resourcesProvider,
                             boolean localPhonePreview) {
        open(context, type, name, owner, info, resourcesProvider, localPhonePreview, UserConfig.selectedAccount);
    }

    private static void open(Context context, int type, String name, TLObject owner,
                             TL_fragment.TL_collectibleInfo info, Theme.ResourcesProvider resourcesProvider,
                             boolean localPhonePreview, int previewAccount) {
        BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        final LocalPhonePreviewGuard guard = localPhonePreview
                ? new LocalPhonePreviewGuard(sheet, previewAccount, owner instanceof TLRPC.User ? ((TLRPC.User) owner).id : 0)
                : null;
        if (guard != null) sheet.setOnDismissListener(guard::detach);
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), 0, dp(16), 0);

        FrameLayout imageContainerView = new FrameLayout(context);
        imageContainerView.setBackground(Theme.createCircleDrawable(dp(80), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        layout.addView(imageContainerView, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 16));

        RLottieImageView imageView = new RLottieImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        int sz = type == TYPE_USERNAME ? 70 : 78;
        imageView.setAnimation(type == TYPE_USERNAME ? R.raw.fragment_username : R.raw.fragment, sz, sz);
        imageView.playAnimation();
        imageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        if (type == TYPE_USERNAME) {
            imageView.setScaleX(0.86f);
            imageView.setScaleY(0.86f);
        } else {
            imageView.setTranslationY(dp(2));
        }
        imageContainerView.addView(imageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        final String ownerName;
        if (owner instanceof TLRPC.User) {
            ownerName = UserObject.getUserName((TLRPC.User) owner);
        } else if (owner instanceof TLRPC.Chat) {
            ownerName = ((TLRPC.Chat) owner).title;
        } else {
            ownerName = "";
        }

        final String money = localPhonePreview && info.amount < 0 ? ""
                : BillingController.getInstance().formatCurrency(info.amount, info.currency);
        final String crypto_money = BillingController.getInstance().formatCurrency(info.crypto_amount, info.crypto_currency);

        String title;
        String message;
        String link;
        if (type == TYPE_USERNAME) {
            title = LocaleController.formatString(R.string.FragmentUsernameTitle, "@" + name);
            message = LocaleController.formatString(R.string.FragmentUsernameMessage, LocaleController.formatShortDateTime(info.purchase_date), crypto_money, TextUtils.isEmpty(money) ? "" : "(" + money + ")");
            link = MessagesController.getInstance(UserConfig.selectedAccount).linkPrefix + "/" + name;
        } else if (type == TYPE_PHONE) {
            title = LocaleController.formatString(R.string.FragmentPhoneTitle, PhoneFormat.getInstance().format("+" + name));
            message = LocaleController.formatString(R.string.FragmentPhoneMessage, LocaleController.formatShortDateTime(info.purchase_date), crypto_money, TextUtils.isEmpty(money) ? "" : "(" + money + ")");
            link = PhoneFormat.getInstance().format("+" + name);
        } else {
            return;
        }

        Runnable copy = link != null ? () -> {
            if (guard != null && !guard.allowAction()) return;
            AndroidUtilities.addToClipboard(link);
            if (type == TYPE_PHONE) {
                BulletinFactory.of(sheet.getContainer(), resourcesProvider).createCopyBulletin(getString(R.string.PhoneCopied)).show();
            } else {
                BulletinFactory.of(sheet.getContainer(), resourcesProvider).createCopyLinkBulletin().show();
            }
        } : null;

        CharSequence titleSpanned = AndroidUtilities.replaceSingleTag(title, copy);

        final SpannableString tonIcon = new SpannableString("TON");
        ColoredImageSpan span = new ColoredImageSpan(R.drawable.mini_gram_16);
        span.setWidth(dp(13));
        tonIcon.setSpan(span, 0, tonIcon.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        CharSequence messageSpanned = AndroidUtilities.replaceCharSequence("TON", AndroidUtilities.replaceTags(message), tonIcon);

        TextView headerView = new LinkSpanDrawable.LinksTextView(context);
        headerView.setTypeface(AndroidUtilities.bold());
        headerView.setGravity(Gravity.CENTER);
        headerView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        headerView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourcesProvider));
        headerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        headerView.setText(titleSpanned);
        layout.addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 42, 0, 42, 0));

        FrameLayout chipLayout = new FrameLayout(context);
        chipLayout.setBackground(Theme.createRoundRectDrawable(dp(28), dp(28), Theme.getColor(Theme.key_groupcreate_spanBackground, resourcesProvider)));

        BackupImageView chipAvatar = new BackupImageView(context);
        chipAvatar.setRoundRadius(dp(28));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(owner);
        chipAvatar.setForUserOrChat(owner, avatarDrawable);
        chipLayout.addView(chipAvatar, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.TOP));

        TextView chipText = new TextView(context);
        chipText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        chipText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chipText.setSingleLine();
        chipText.setText(Emoji.replaceEmoji(ownerName, chipText.getPaint().getFontMetricsInt(), false));
        chipLayout.addView(chipText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 37, 0, 10, 0));

        layout.addView(chipLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, Gravity.CENTER_HORIZONTAL, 42, 10, 42, 18));

        TextView descriptionView = new TextView(context);
        descriptionView.setGravity(Gravity.CENTER);
        descriptionView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descriptionView.setText(messageSpanned);
        layout.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 19));

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString(R.string.FragmentUsernameOpen), false);
        button.setOnClickListener(v -> {
            if (guard != null && !guard.allowAction()) return;
            Browser.openUrl(context, info.url);
        });
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 6, 0, 6, 0));

        if (copy != null) {
            ButtonWithCounterView button2 = new ButtonWithCounterView(context, resourcesProvider).setRound().setNeutral();
            button2.setText(getString(type == TYPE_USERNAME ? R.string.FragmentUsernameCopy : R.string.FragmentPhoneCopy), false);
            button2.setOnClickListener(v -> {
                copy.run();
                sheet.dismiss();
            });
            layout.addView(button2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 6, 6, 6, 0));
        }

        sheet.setCustomView(layout);
        sheet.show();
        if (guard != null) guard.attach();
    }

    /**
     * Opens a local-only Fragment-style preview for the visual +888 number.
     * Nothing is purchased and no server account data is changed. The displayed
     * price is a user preference belonging to the current account identity.
     */
    public static void openLocalPhone(
        Context context,
        int currentAccount,
        String phone,
        TLRPC.User owner,
        Theme.ResourcesProvider resourcesProvider
    ) {
        if (context == null || owner == null || TextUtils.isEmpty(phone)
                || currentAccount < 0 || currentAccount >= UserConfig.MAX_ACCOUNT_COUNT
                || currentAccount != UserConfig.selectedAccount
                || owner.id <= 0 || owner.id != UserConfig.getInstance(currentAccount).getClientUserId()
                || !LumaAnonymousNumber.isEnabled(currentAccount)
                || !phone.equals(LumaAnonymousNumber.getPhone(currentAccount))) {
            return;
        }

        TL_fragment.TL_collectibleInfo preview = new TL_fragment.TL_collectibleInfo();
        preview.purchase_date = LumaAnonymousNumber.getPurchaseDate(currentAccount);
        preview.crypto_currency = "TON";
        preview.crypto_amount = LumaAnonymousNumber.getPriceNanoTon(currentAccount);
        preview.currency = "USD";
        preview.amount = LumaAnonymousNumber.getUsdCents(preview.crypto_amount,
                MessagesController.getInstance(currentAccount).config.tonUsdRate.get());
        // A general information link, never a claim that this user owns a real listing.
        preview.url = "https://fragment.com/numbers";
        open(context, TYPE_PHONE, phone, owner, preview, resourcesProvider, true, currentAccount);
    }

    /** A local display cannot survive expiry/logout and become a different account's sheet. */
    private static final class LocalPhonePreviewGuard implements NotificationCenter.NotificationCenterDelegate {
        private final BottomSheet sheet;
        private final int account;
        private final long owner;
        private boolean attached, closed;

        LocalPhonePreviewGuard(BottomSheet sheet, int account, long owner) {
            this.sheet = sheet;
            this.account = account;
            this.owner = owner;
        }

        private boolean isCurrent() {
            return !closed && owner > 0 && account == UserConfig.selectedAccount
                    && owner == UserConfig.getInstance(account).getClientUserId()
                    && LumaAnonymousNumber.isEnabled(account);
        }

        boolean allowAction() {
            if (isCurrent()) return true;
            close();
            return false;
        }

        void attach() {
            if (!allowAction() || attached) return;
            attached = true;
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.lunagramSubscriptionChanged);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.appDidLogout);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
        }

        void detach() {
            closed = true;
            if (!attached) return;
            attached = false;
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.lunagramSubscriptionChanged);
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.appDidLogout);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
        }

        private void close() {
            if (closed) return;
            detach();
            sheet.dismiss();
        }

        @Override
        public void didReceivedNotification(int id, int changedAccount, Object... args) {
            if (id == NotificationCenter.appDidLogout || id == NotificationCenter.activeAccountChanged
                    || !isCurrent()) close();
        }
    }

}
