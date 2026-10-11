package org.telegram.ui.Gifts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LumaBuildPolicy;
import org.telegram.messenger.LumaLocalGifts;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Stars.StarGiftSheet;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/** Read-only local display. Never opens the real gift transaction sheet. */
public final class LumaLocalGiftPreview {
    private LumaLocalGiftPreview() {}

    public static String getRemoveTitle() {
        return getString(LumaBuildPolicy.isPersonalEdition() ? R.string.LumaPersonalGiftRemove : R.string.LumaLocalGiftRemove);
    }

    public static String getRemoveInfo() {
        return getString(LumaBuildPolicy.isPersonalEdition() ? R.string.LumaPersonalGiftRemoveInfo : R.string.LumaLocalGiftRemoveInfo);
    }

    public static String getRemovedMessage() {
        return getString(LumaBuildPolicy.isPersonalEdition() ? R.string.LumaPersonalGiftRemoved : R.string.LumaLocalGiftRemoved);
    }

    public static void show(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift) {
        if (fragment == null || gift == null) return;
        int account = fragment.getCurrentAccount();
        Context context = fragment.getParentActivity();
        if (context == null || gift.ownerUserId <= 0
                || gift.ownerUserId != UserConfig.getInstance(account).getClientUserId()
                || !(gift.gift instanceof TL_stars.TL_starGiftUnique)) return;
        TL_stars.TL_starGiftUnique unique = (TL_stars.TL_starGiftUnique) gift.gift;
        if (unique.attributes == null || unique.resell_amount != null) return;
        Theme.ResourcesProvider resources = fragment.getResourceProvider();
        if (LumaBuildPolicy.isPersonalEdition()) {
            if (account != UserConfig.selectedAccount) return;
            showPersonal(fragment, gift, unique, context, account, resources);
            return;
        }
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));

        GiftSheet.GiftCell card = new GiftSheet.GiftCell(context, account, resources);
        card.setStarsGift(gift, true, false);
        content.addView(card, LayoutHelper.createLinear(200, 220, Gravity.CENTER_HORIZONTAL,
                0, 0, 0, LumaBuildPolicy.isPersonalEdition() ? 16 : 0));

        if (!LumaBuildPolicy.isPersonalEdition()) {
            TextView local = new TextView(context);
            local.setText(getString(R.string.LumaLocalGiftPreviewInfo));
            local.setTextSize(13);
            local.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resources));
            local.setGravity(Gravity.CENTER);
            content.addView(local, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 10, 0, 16));
        }

        content.addView(createTable(context, account, unique, resources, false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                int limit = Math.min(dp(500), View.MeasureSpec.getSize(height));
                if (limit <= 0) limit = dp(500);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(content);
        fragment.showDialog(new AlertDialog.Builder(context, resources)
                .setTitle(unique.title + " #" + LocaleController.formatNumber(unique.num, ','))
                .setView(scroll)
                .setPositiveButton(getString(R.string.OK), null)
                .setNegativeButton(getRemoveTitle(), (dialog, which) -> confirmRemove(fragment, gift))
                .create());
    }

    private static void showPersonal(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift,
            TL_stars.TL_starGiftUnique unique, Context context, int account,
            Theme.ResourcesProvider resources) {
        BottomSheet sheet = new BottomSheet(context, false, resources);
        sheet.setApplyTopPadding(false);
        sheet.setApplyBottomPadding(false);
        sheet.setAllowNestedScroll(true);
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resources));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        PreviewTopView top = new PreviewTopView(context, account, gift.ownerUserId, resources, () -> {
            content.setVisibility(View.GONE);
            sheet.dismiss();
        });
        // TopView is Telegram's visual header only. All transaction callbacks are null,
        // and owner/host/link capabilities remain false even for this account's marker.
        top.setGift(unique, false, false, false, false, false);
        top.setText(0, unique.title + " #" + LocaleController.formatNumber(unique.num, ','), null, null, null);
        top.optionsView.setVisibility(View.GONE);
        content.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(createTable(context, account, unique, resources, true),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 20, 20, 16));

        ButtonWithCounterView done = new ButtonWithCounterView(context, resources);
        done.setText(getString(R.string.OK), false);
        done.setOnClickListener(v -> sheet.dismiss());
        content.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 20, 0, 20, 0));
        BottomSheet.BottomSheetCell remove = new BottomSheet.BottomSheetCell(context, 0, resources);
        remove.setTextAndIcon(getRemoveTitle(), 0);
        remove.setTextColor(Theme.getColor(Theme.key_text_RedBold, resources));
        remove.setGravity(Gravity.CENTER);
        remove.setOnClickListener(v -> {
            sheet.dismiss();
            confirmRemove(fragment, gift);
        });
        content.addView(remove, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 20, 4, 20, 8));

        NestedScrollView scroll = new NestedScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        sheet.setCustomView(scroll);
        sheet.setOnDismissListener(top::stopPreview);
        fragment.showDialog(sheet);
    }

    /** Reuses NFT artwork, pattern and radial backdrop, never the transaction sheet. */
    private static final class PreviewTopView extends StarGiftSheet.TopView
            implements NotificationCenter.NotificationCenterDelegate {
        private final int account;
        private final long owner;
        private final Runnable dismiss;
        private final AnimatedEmojiDrawable[] previewPatterns = new AnimatedEmojiDrawable[2];
        private boolean stopped;
        private boolean animationRunning;

        PreviewTopView(Context context, int account, long owner, Theme.ResourcesProvider resources, Runnable dismiss) {
            super(context, resources, dismiss, null, null, null, null, null, null, null);
            this.account = account;
            this.owner = owner;
            this.dismiss = dismiss;
            for (int i = 0; i < imageLayout.getChildCount(); i++) {
                View child = imageLayout.getChildAt(i);
                if (child instanceof BackupImageView) {
                    ImageReceiver receiver = ((BackupImageView) child).getImageReceiver();
                    receiver.setCurrentAccount(account);
                    receiver.setUniqKeyPrefix("luma_gift_preview_model_" + java.util.UUID.randomUUID() + "_");
                    receiver.setAllowDecodeSingleFrame(true);
                    receiver.setAutoRepeat(2);
                }
            }
            setAnimationRunning(false);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    // Only the sheet's top corners are rounded; the table follows flush below.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + dp(14), dp(14));
                }
            });
            setClipToOutline(true);
        }

        @Override protected void onMeasure(int width, int height) {
            // TopView also contains invisible upgrade/crafting pages. They must not
            // reserve their height in this read-only, info-page-only scroll container.
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            setMeasuredDimension(getMeasuredWidth(), getFinalHeight());
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.appDidLogout);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
            if (account != UserConfig.selectedAccount || owner != UserConfig.getInstance(account).getClientUserId()) {
                stopPreview();
                dismiss.run();
                return;
            }
            setAnimationRunning(!stopped && animationsAllowed());
        }

        @Override protected void onDetachedFromWindow() {
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.appDidLogout);
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.activeAccountChanged);
            setAnimationRunning(false);
            super.onDetachedFromWindow(); // Native TopView detaches its animated pattern too.
        }

        @Override public void didReceivedNotification(int id, int notificationAccount, Object... args) {
            if (id == NotificationCenter.appDidLogout || account != UserConfig.selectedAccount
                    || owner != UserConfig.getInstance(account).getClientUserId()) {
                stopPreview();
                dismiss.run();
            }
        }

        void stopPreview() {
            stopped = true;
            setAnimationRunning(false);
        }

        private static boolean animationsAllowed() {
            try {
                float scale = AndroidUtilities.getAnimatorDurationScale();
                return SharedConfig.animationsEnabled() && scale > 0 && !Float.isInfinite(scale)
                        && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
            } catch (Exception ignore) {
                return false;
            }
        }

        @Override protected AnimatedEmojiDrawable createReadOnlyPatternDrawable(int page, TLRPC.Document document) {
            if (document == null) return null;
            // Never change the globally cached emoji or its shared Lottie instance.
            // This constructor defers image loading until attach; a per-preview key
            // is applied first, keeping animation state isolated from other screens.
            AnimatedEmojiDrawable drawable = new AnimatedEmojiDrawable(
                    AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS, account, document);
            ImageReceiver receiver = drawable.getImageReceiver();
            receiver.setUniqKeyPrefix("luma_gift_preview_" + java.util.UUID.randomUUID() + "_");
            setReceiverAnimation(receiver, animationRunning);
            previewPatterns[page] = drawable;
            return drawable;
        }

        private void setAnimationRunning(boolean running) {
            animationRunning = running;
            for (int i = 0; i < imageLayout.getChildCount(); i++) {
                View child = imageLayout.getChildAt(i);
                if (!(child instanceof BackupImageView)) continue;
                setReceiverAnimation(((BackupImageView) child).getImageReceiver(), running);
            }
            for (AnimatedEmojiDrawable pattern : previewPatterns) {
                if (pattern != null) setReceiverAnimation(pattern.getImageReceiver(), running);
            }
        }

        private static void setReceiverAnimation(ImageReceiver receiver, boolean running) {
            receiver.setAllowDecodeSingleFrame(true);
            receiver.setAllowStartAnimation(running);
            receiver.setAllowStartLottieAnimation(running);
            if (running) receiver.startAnimation();
            else receiver.stopAnimation();
        }
    }

    private static TableView createTable(Context context, int account, TL_stars.TL_starGiftUnique unique,
            Theme.ResourcesProvider resources, boolean nativeAttributes) {
        TableView table = new TableView(context, resources);
        LinearLayout owner = new LinearLayout(context);
        owner.setOrientation(LinearLayout.HORIZONTAL);
        owner.setGravity(Gravity.CENTER_VERTICAL);
        owner.setPadding(dp(12), dp(8), dp(12), dp(8));
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(14));
        AvatarDrawable placeholder = new AvatarDrawable();
        if (self != null) {
            placeholder.setInfo(self);
            avatar.setForUserOrChat(self, placeholder);
        } else {
            avatar.setImageDrawable(placeholder);
        }
        owner.addView(avatar, LayoutHelper.createLinear(28, 28));
        TextView name = new TextView(context);
        name.setText(self == null ? getString(R.string.LumaLocalGiftOwnAccount) : UserObject.getUserName(self));
        name.setTextSize(14);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        owner.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        table.addRowUnpadded(getString(R.string.Gift2Owner), owner);
        for (TL_stars.StarGiftAttribute attribute : unique.attributes) {
            if (nativeAttributes) {
                if (attribute != null && attribute.name != null) StarGiftSheet.addAttributeRow(table, attribute);
                continue;
            }
            int label = attribute instanceof TL_stars.starGiftAttributeModel ? R.string.Gift2AttributeModel
                    : attribute instanceof TL_stars.starGiftAttributeBackdrop ? R.string.Gift2AttributeBackdrop
                    : attribute instanceof TL_stars.starGiftAttributePattern ? R.string.Gift2AttributeSymbol : 0;
            if (label != 0 && attribute.name != null) {
                CharSequence rarity = StarGiftSheet.getRarityName(attribute.rarity, null);
                table.addRow(getString(label), attribute.name + (rarity.length() == 0 ? "" : " · " + rarity));
            }
        }
        return table;
    }

    public static void confirmRemove(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift) {
        Context context = fragment.getParentActivity();
        final int account = fragment.getCurrentAccount();
        final long owner = gift.ownerUserId;
        if (context == null || owner != UserConfig.getInstance(account).getClientUserId()) return;
        fragment.showDialog(new AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(getRemoveTitle())
                .setMessage(getRemoveInfo())
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
                    boolean removed = LumaLocalGifts.remove(account, gift);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (owner != UserConfig.getInstance(account).getClientUserId()) return;
                        if (removed) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.lumaLocalGiftsChanged, owner);
                        if (fragment.getParentActivity() != null) BulletinFactory.of(fragment).createSimpleBulletin(
                                removed ? R.raw.done : R.raw.chats_infotip,
                                removed ? getRemovedMessage() : getString(R.string.LumaLocalGiftFailed)).show();
                    });
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }
}
