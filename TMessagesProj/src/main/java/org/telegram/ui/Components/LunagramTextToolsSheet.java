package org.telegram.ui.Components;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramTextTools;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LunagramFeatureGate;

/** Offline previews. Neither opening nor choosing an operation modifies or sends the source draft. */
public final class LunagramTextToolsSheet {
    public interface Apply { boolean apply(CharSequence value); }
    public interface History {
        boolean canUndo(); boolean canRedo(); boolean undo(); boolean redo();
    }
    private final BaseFragment host;
    private final int account;
    private final LunagramMediaAccess.Session access;
    private final String source;
    private final Apply apply;
    private final History history;
    private final BooleanSupplier contextValid;
    private LunagramTextToolsSheet(BaseFragment host, int account, CharSequence source, Apply apply, History history, BooleanSupplier contextValid) {
        this.host = host; this.account = account; this.source = LunagramTextTools.requireInput(source);
        this.apply = apply; this.history = history; access = LunagramMediaAccess.forAccount(account);
        this.contextValid = contextValid;
    }
    public static void show(BaseFragment host, int account, CharSequence source, Apply apply) {
        show(host, account, source, apply, null, null);
    }
    public static void show(BaseFragment host, int account, CharSequence source, Apply apply, History history) {
        show(host, account, source, apply, history, null);
    }
    public static void show(BaseFragment host, int account, CharSequence source, Apply apply, BooleanSupplier contextValid) {
        show(host, account, source, apply, null, contextValid);
    }
    public static void show(BaseFragment host, int account, CharSequence source, Apply apply, History history, BooleanSupplier contextValid) {
        if (host == null || host.getParentActivity() == null || host.getCurrentAccount() != account
                || contextValid != null && !contextValid.getAsBoolean()
                || account != UserConfig.selectedAccount || !LunagramFeatureGate.require(host, LunagramFeatureCatalog.Feature.TEXT_TOOLS)) return;
        try { new LunagramTextToolsSheet(host, account, source, apply, history, contextValid).open(); }
        catch (IllegalArgumentException error) { showError(host, R.string.LutTooLong); }
    }
    private boolean valid() {
        return host.getParentActivity() != null && !host.getParentActivity().isFinishing()
                && host.getCurrentAccount() == account && account == UserConfig.selectedAccount
                && (contextValid == null || contextValid.getAsBoolean())
                && access.canUse(LunagramFeatureCatalog.Feature.TEXT_TOOLS);
    }
    private void open() {
        if (!valid()) return;
        ArrayList<CharSequence> labels = new ArrayList<>(); ArrayList<Runnable> actions = new ArrayList<>();
        if (history != null && history.canUndo()) add(labels, actions, R.string.LutUndo, () -> { if (!history.undo()) showError(host, R.string.LutDraftChanged); });
        if (history != null && history.canRedo()) add(labels, actions, R.string.LutRedo, () -> { if (!history.redo()) showError(host, R.string.LutDraftChanged); });
        add(labels, actions, R.string.LutLayoutRu, () -> preview(LunagramTextTools.keyboardLayout(source, true)));
        add(labels, actions, R.string.LutLayoutEn, () -> preview(LunagramTextTools.keyboardLayout(source, false)));
        add(labels, actions, R.string.LutReplace, this::replaceForm);
        add(labels, actions, R.string.LutWhitespace, () -> preview(LunagramTextTools.normalizeWhitespace(source)));
        add(labels, actions, R.string.LutStatistics, this::statistics);
        add(labels, actions, R.string.LutCompare, this::compareForm);
        host.showDialog(builder(R.string.LutTextToolsTitle).setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
            if (!valid() || which < 0 || which >= actions.size()) return;
            try { actions.get(which).run(); } catch (IllegalArgumentException error) { showError(host, R.string.LutTooLong); }
        }).create());
    }
    private void add(ArrayList<CharSequence> labels, ArrayList<Runnable> actions, int name, Runnable action) {
        labels.add(s(name)); actions.add(action);
    }
    /** Also used for a combined selection: editable preview, explicit apply, never automatic send. */
    public static void showDraftPreview(BaseFragment host, int account, CharSequence source, Apply apply) {
        showDraftPreview(host, account, source, apply, null);
    }
    public static void showDraftPreview(BaseFragment host, int account, CharSequence source, Apply apply, BooleanSupplier contextValid) {
        if (host == null || host.getParentActivity() == null || host.getCurrentAccount() != account
                || contextValid != null && !contextValid.getAsBoolean()
                || account != UserConfig.selectedAccount || !LunagramFeatureGate.require(host, LunagramFeatureCatalog.Feature.TEXT_TOOLS)) return;
        try { LunagramTextToolsSheet sheet = new LunagramTextToolsSheet(host, account, source, apply, null, contextValid); sheet.preview(sheet.source); }
        catch (IllegalArgumentException error) { showError(host, R.string.LutTooLong); }
    }
    private void preview(String value) {
        if (!valid()) return;
        LinearLayout content = column(); EditTextBoldCursor result = input(R.string.LutPreview, false); result.setText(value);
        content.addView(label(s(R.string.LutPlainTextHint)), LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        content.addView(result, LayoutHelper.createLinear(-1, -2));
        AlertDialog dialog = builder(R.string.LutPreview).setView(scroll(content))
                .setPositiveButton(s(apply == null ? R.string.Copy : R.string.LutApplyDraft), null).setNegativeButton(s(R.string.Cancel), null).create();
        host.showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!valid()) { dialog.dismiss(); return; }
            String text;
            try { text = LunagramTextTools.requireInput(result.getText()); }
            catch (IllegalArgumentException error) { result.setError(s(R.string.LutTooLong)); return; }
            if (apply == null) AndroidUtilities.addToClipboard(text);
            else if (!apply.apply(text)) { showError(host, R.string.LutDraftChanged); return; }
            dialog.dismiss();
        });
    }
    private void replaceForm() {
        LinearLayout content = column(); EditTextBoldCursor find = input(R.string.LutFind, true), replacement = input(R.string.LutReplaceWith, true);
        content.addView(find, LayoutHelper.createLinear(-1, -2)); content.addView(replacement, LayoutHelper.createLinear(-1, -2, 0, 8, 0, 0));
        content.addView(label(s(R.string.LutLiteralHint)), LayoutHelper.createLinear(-1, -2, 0, 12, 0, 0));
        AlertDialog dialog = builder(R.string.LutReplace).setView(scroll(content)).setPositiveButton(s(R.string.LutPreview), null).setNegativeButton(s(R.string.Cancel), null).create();
        host.showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!valid()) { dialog.dismiss(); return; }
            if (find.length() == 0) { find.setError(s(R.string.LutFindRequired)); return; }
            try {
                LunagramTextTools.Replacement result = LunagramTextTools.replace(source, find.getText().toString(), replacement.getText().toString(), true);
                if (result.matches == 0) { find.setError(s(R.string.LutNoMatches)); return; }
                dialog.dismiss(); preview(result.text);
            } catch (IllegalArgumentException error) { replacement.setError(s(R.string.LutTooLong)); }
        });
    }
    private void statistics() {
        LunagramTextTools.Statistics value = LunagramTextTools.statistics(source);
        host.showDialog(builder(R.string.LutStatistics).setMessage(LocaleController.formatString(R.string.LutStatisticsBody,
                value.codePoints, value.words, value.lines, value.utf16Units, value.utf8Bytes))
                .setPositiveButton(s(R.string.OK), null).create());
    }
    private void compareForm() {
        LinearLayout content = column(); EditTextBoldCursor other = input(R.string.LutSecondText, false);
        content.addView(label(s(R.string.LutCompareHint)), LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
        content.addView(other, LayoutHelper.createLinear(-1, -2));
        AlertDialog dialog = builder(R.string.LutCompare).setView(scroll(content)).setPositiveButton(s(R.string.LutCompare), null).setNegativeButton(s(R.string.Cancel), null).create();
        host.showDialog(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!valid()) { dialog.dismiss(); return; }
            try {
                LunagramTextTools.Comparison diff = LunagramTextTools.compare(source, other.getText());
                dialog.dismiss();
                if (diff.identical()) { host.showDialog(builder(R.string.LutCompare).setMessage(s(R.string.LutIdentical)).setPositiveButton(s(R.string.OK), null).create()); return; }
                LinearLayout result = column();
                result.addView(label(s(R.string.LutCompareLegend)), LayoutHelper.createLinear(-1, -2, 0, 0, 0, 12));
                SpannableStringBuilder text = new SpannableStringBuilder(diff.prefix);
                int start = text.length(); text.append(diff.removed);
                if (text.length() > start) {
                    text.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_text_RedRegular, host.getResourceProvider())), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new StrikethroughSpan(), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                start = text.length(); text.append(diff.added);
                if (text.length() > start) {
                    text.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextBlue, host.getResourceProvider())), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.setSpan(new UnderlineSpan(), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                text.append(diff.suffix); TextView body = label(text); body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); body.setTextIsSelectable(true);
                body.setCustomSelectionActionModeCallback(new LunagramTextSelectionGuard(this::valid, () -> body.setText("")));
                result.addView(body, LayoutHelper.createLinear(-1, -2));
                host.showDialog(builder(R.string.LutCompare).setView(scroll(result)).setPositiveButton(s(R.string.OK), null).create());
            } catch (IllegalArgumentException error) { other.setError(s(R.string.LutTooLong)); }
        });
    }
    private AlertDialog.Builder builder(int title) { return new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider()).setTitle(s(title)); }
    private static String s(int id) { return LocaleController.getString(id); }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(host.getParentActivity()); view.setOrientation(LinearLayout.VERTICAL); view.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8)); return view;
    }
    private TextView label(CharSequence value) {
        TextView view = new TextView(host.getParentActivity()) {
            @Override public boolean onTextContextMenuItem(int id) {
                if (!valid()) { setText(""); return true; }
                return super.onTextContextMenuItem(id);
            }
        }; view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider())); view.setText(value); return view;
    }
    private EditTextBoldCursor input(int hint, boolean single) {
        EditTextBoldCursor view = new EditTextBoldCursor(host.getParentActivity()) {
            @Override public boolean onTextContextMenuItem(int id) {
                if (!valid()) { setText(""); return true; }
                return super.onTextContextMenuItem(id);
            }
        };
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        view.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, host.getResourceProvider())); view.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        view.setHint(s(hint)); view.setContentDescription(s(hint)); view.setSingleLine(single);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (single ? 0 : InputType.TYPE_TEXT_FLAG_MULTI_LINE) | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setMinHeight(AndroidUtilities.dp(48)); if (!single) view.setMinLines(3);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LunagramTextTools.MAX_INPUT)});
        view.setCustomSelectionActionModeCallback(new LunagramTextSelectionGuard(this::valid, () -> view.setText("")));
        return view;
    }
    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(host.getParentActivity()) {
            @Override protected void onMeasure(int width, int height) {
                int max = AndroidUtilities.dp(320);
                if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED) max = Math.min(max, MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(content); return scroll;
    }
    public static void showError(BaseFragment host, int message) {
        if (host != null && host.getParentActivity() != null) host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(s(R.string.LutTextToolsTitle)).setMessage(s(message)).setPositiveButton(s(R.string.OK), null).create());
    }
}
