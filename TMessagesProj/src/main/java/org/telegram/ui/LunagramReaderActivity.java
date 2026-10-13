package org.telegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramTextTools;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LunagramTextSelectionGuard;

/** Transient, plain-text reader. Caller must exclude secret/protected/ephemeral messages. */
public final class LunagramReaderActivity extends BaseFragment {
    private String source;
    private final int ownerAccount;
    private final LunagramMediaAccess.Session access;
    private final BooleanSupplier contextValid;
    private int[] paragraphs, matches = new int[0];
    private int paragraphIndex, matchIndex;
    private String query = "";
    private TextView body, position, previous, next;
    private ScrollView scroll;
    public LunagramReaderActivity(int account, CharSequence text) {
        this(account, text, null);
    }
    public LunagramReaderActivity(int account, CharSequence text, BooleanSupplier contextValid) {
        setCurrentAccount(account); ownerAccount = account;
        this.contextValid = contextValid;
        source = LunagramTextTools.requireInput(text); paragraphs = LunagramTextTools.paragraphStarts(source);
        access = LunagramMediaAccess.forAccount(account);
    }
    private boolean allowed() { return currentAccount == ownerAccount && UserConfig.selectedAccount == ownerAccount
            && (contextValid == null || contextValid.getAsBoolean()) && access.canUse(LunagramFeatureCatalog.Feature.READING_TOOLS); }
    @Override public boolean onFragmentCreate() { return allowed() && super.onFragmentCreate(); }
    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back); actionBar.setTitle(LocaleController.getString(R.string.LutReadingTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() { @Override public void onItemClick(int id) { if (id == -1) finishFragment(); } });
        ActionBarMenuItem search = actionBar.createMenu().addItem(1, R.drawable.ic_ab_search).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override public void onTextChanged(EditText edit) {
                        if (!allowed()) { closeExpired(); return; }
                        if (edit.length() > 256) return;
                        query = edit.getText().toString(); matches = LunagramTextTools.find(source, query); matchIndex = 0;
                        paintSearch(); navigate(false, true);
                    }
                    @Override public void onSearchCollapse() { query = ""; matches = new int[0]; paintSearch(); updatePosition(); }
                });
        search.setSearchFieldHint(LocaleController.getString(R.string.LutFind));
        search.getSearchField().setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        LinearLayout frame = new LinearLayout(context); frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
        scroll = new ScrollView(context); scroll.setFillViewport(true);
        body = new TextView(context) {
            @Override public boolean onTextContextMenuItem(int id) {
                if (!allowed()) { closeExpired(); return true; }
                return super.onTextContextMenuItem(id);
            }
        }; body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        body.setLineSpacing(AndroidUtilities.dp(3), 1f); body.setTextIsSelectable(true);
        body.setCustomSelectionActionModeCallback(new LunagramTextSelectionGuard(this::allowed, this::closeExpired));
        body.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(24));
        body.setText(allowed() ? source : ""); scroll.addView(body);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout controls = new LinearLayout(context); controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        previous = button(context, R.string.LutPrevious, () -> navigate(false, false));
        next = button(context, R.string.LutNext, () -> navigate(true, false));
        position = new TextView(context); position.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); position.setGravity(Gravity.CENTER);
        position.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        controls.addView(previous, LayoutHelper.createLinear(-2, -2)); controls.addView(position, new LinearLayout.LayoutParams(0, -2, 1f)); controls.addView(next, LayoutHelper.createLinear(-2, -2));
        frame.addView(controls, LayoutHelper.createLinear(-1, -2)); updatePosition(); return fragmentView = frame;
    }
    private TextView button(Context context, int label, Runnable click) {
        TextView button = new TextView(context); button.setText(LocaleController.getString(label)); button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); button.setMinHeight(AndroidUtilities.dp(48)); button.setMinWidth(AndroidUtilities.dp(64));
        button.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourceProvider));
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourceProvider)));
        button.setOnClickListener(v -> { if (allowed()) click.run(); else closeExpired(); }); return button;
    }
    private void paintSearch() {
        if (body == null) return;
        if (!allowed()) { closeExpired(); return; }
        SpannableStringBuilder text = new SpannableStringBuilder(source);
        int color = Theme.getColor(Theme.key_dialogButtonSelector, resourceProvider);
        for (int at : matches) text.setSpan(new BackgroundColorSpan(color), at, at + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setText(text);
    }
    private void navigate(boolean forward, boolean initial) {
        if (body == null || !allowed()) return;
        boolean searching = !query.isEmpty(); int[] offsets = searching ? matches : paragraphs;
        int index = searching ? matchIndex : paragraphIndex;
        if (!initial) index += forward ? 1 : -1;
        index = Math.max(0, Math.min(offsets.length - 1, index));
        if (searching) matchIndex = index; else paragraphIndex = index;
        updatePosition();
        if (offsets.length == 0) return;
        final int offset = offsets[index];
        body.post(() -> { if (allowed() && body != null && body.getLayout() != null) scroll.smoothScrollTo(0, body.getLayout().getLineTop(body.getLayout().getLineForOffset(offset)) + body.getPaddingTop()); });
    }
    private void updatePosition() {
        if (position == null) return;
        boolean searching = !query.isEmpty(); int count = searching ? matches.length : paragraphs.length, index = searching ? matchIndex : paragraphIndex;
        position.setText(count == 0 ? LocaleController.getString(searching ? R.string.LutNoMatches : R.string.LutEmpty)
                : LocaleController.formatString(searching ? R.string.LutMatchPosition : R.string.LutParagraphPosition, index + 1, count));
        previous.setEnabled(count > 0 && index > 0); next.setEnabled(count > 0 && index + 1 < count);
        previous.setAlpha(previous.isEnabled() ? 1f : .45f); next.setAlpha(next.isEnabled() ? 1f : .45f);
    }
    private void closeExpired() { source = ""; if (body != null) body.setText(""); finishFragment(); }
    @Override public void onResume() { super.onResume(); if (!allowed()) closeExpired(); }
    @Override public void onFragmentDestroy() { source = ""; paragraphs = matches = new int[0]; body = null; super.onFragmentDestroy(); }
}
