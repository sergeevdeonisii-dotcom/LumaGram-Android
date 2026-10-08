package org.telegram.ui.Components;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;

/** Dialog inputs must follow the Telegram theme, not the Activity's system theme. */
public final class LumaDialogInput {
    private LumaDialogInput() {}

    public static EditTextBoldCursor create(Context context, int inputType, boolean multiline,
                                            Theme.ResourcesProvider resourcesProvider) {
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        field.setCursorWidth(1.5f);
        field.setBackground(Theme.createEditTextDrawable(context,
                Theme.getColor(Theme.key_dialogInputField, resourcesProvider),
                Theme.getColor(Theme.key_dialogInputFieldActivated, resourcesProvider)));
        field.setInputType(inputType);
        field.setSingleLine(!multiline);
        field.setGravity((multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL)
                | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        field.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | (multiline ? EditorInfo.IME_FLAG_NO_ENTER_ACTION : EditorInfo.IME_ACTION_DONE));
        return field;
    }

    public static FrameLayout wrap(EditTextBoldCursor field) {
        FrameLayout container = new FrameLayout(field.getContext());
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 0, 24, 8));
        return container;
    }
}
