package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/** A local donation address display. No wallet connection, requests or transfers. */
public final class LunagramSupport {
    public static final String TON_ADDRESS = "UQDwZfLQqfQakdFG642jd24xjILmNKfXoIbjsoEuGMoLX-7G";

    private LunagramSupport() {
    }

    public static void show(BaseFragment fragment) {
        Context context = fragment.getParentActivity();
        if (context == null) return;
        Theme.ResourcesProvider resources = fragment.getResourceProvider();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), dp(8));

        TextView description = new TextView(context);
        description.setText(getString(R.string.LunagramSupportDescription));
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        description.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
        description.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView address = new TextView(context);
        address.setText(TON_ADDRESS);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        address.setTypeface(Typeface.MONOSPACE);
        address.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resources));
        address.setTextIsSelectable(true);
        address.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setGravity(Gravity.LEFT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            address.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            address.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        content.addView(address, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        fragment.showDialog(new AlertDialog.Builder(context, resources)
                .setTitle(getString(R.string.LunagramSupportTitle))
                .setView(content)
                .setPositiveButton(getString(R.string.LunagramSupportCopy), (dialog, which) -> {
                    if (AndroidUtilities.addToClipboard(TON_ADDRESS)) {
                        BulletinFactory.of(fragment).createCopyBulletin(getString(R.string.WalletAddressCopied)).show();
                    } else {
                        Toast.makeText(context, getString(R.string.ErrorOccurred), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(getString(R.string.Close), null)
                .create());
    }
}
