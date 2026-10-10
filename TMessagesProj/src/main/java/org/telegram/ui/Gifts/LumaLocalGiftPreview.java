package org.telegram.ui.Gifts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LumaLocalGifts;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TableView;
import org.telegram.ui.Stars.StarGiftSheet;

/** Read-only local display. Never opens the real gift transaction sheet. */
public final class LumaLocalGiftPreview {
    private LumaLocalGiftPreview() {}

    public static void show(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift) {
        int account = fragment.getCurrentAccount();
        Context context = fragment.getParentActivity();
        if (context == null || gift.ownerUserId != UserConfig.getInstance(account).getClientUserId()
                || !(gift.gift instanceof TL_stars.TL_starGiftUnique)) return;
        TL_stars.TL_starGiftUnique unique = (TL_stars.TL_starGiftUnique) gift.gift;
        Theme.ResourcesProvider resources = fragment.getResourceProvider();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));

        GiftSheet.GiftCell card = new GiftSheet.GiftCell(context, account, resources);
        card.setStarsGift(gift, true, false);
        content.addView(card, LayoutHelper.createLinear(200, 220, Gravity.CENTER_HORIZONTAL));

        TextView local = new TextView(context);
        local.setText(getString(R.string.LumaLocalGiftPreviewInfo));
        local.setTextSize(13);
        local.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resources));
        local.setGravity(Gravity.CENTER);
        content.addView(local, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 10, 0, 16));

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
            int label = attribute instanceof TL_stars.starGiftAttributeModel ? R.string.Gift2AttributeModel
                    : attribute instanceof TL_stars.starGiftAttributeBackdrop ? R.string.Gift2AttributeBackdrop
                    : attribute instanceof TL_stars.starGiftAttributePattern ? R.string.Gift2AttributeSymbol : 0;
            if (label != 0 && attribute.name != null) {
                CharSequence rarity = StarGiftSheet.getRarityName(attribute.rarity, null);
                table.addRow(getString(label), attribute.name + (rarity.length() == 0 ? "" : " · " + rarity));
            }
        }
        content.addView(table, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
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
                .setNegativeButton(getString(R.string.LumaLocalGiftRemove), (dialog, which) -> confirmRemove(fragment, gift))
                .create());
    }

    public static void confirmRemove(BaseFragment fragment, LumaLocalGifts.LocalSavedGift gift) {
        Context context = fragment.getParentActivity();
        final int account = fragment.getCurrentAccount();
        final long owner = gift.ownerUserId;
        if (context == null || owner != UserConfig.getInstance(account).getClientUserId()) return;
        fragment.showDialog(new AlertDialog.Builder(context, fragment.getResourceProvider())
                .setTitle(getString(R.string.LumaLocalGiftRemove))
                .setMessage(getString(R.string.LumaLocalGiftRemoveInfo))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> Utilities.globalQueue.postRunnable(() -> {
                    boolean removed = LumaLocalGifts.remove(account, gift);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (owner != UserConfig.getInstance(account).getClientUserId()) return;
                        if (removed) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.lumaLocalGiftsChanged, owner);
                        if (fragment.getParentActivity() != null) BulletinFactory.of(fragment).createSimpleBulletin(
                                removed ? R.raw.done : R.raw.chats_infotip,
                                getString(removed ? R.string.LumaLocalGiftRemoved : R.string.LumaLocalGiftFailed)).show();
                    });
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }
}
