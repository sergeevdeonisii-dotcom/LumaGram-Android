package org.telegram.ui.Components;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** Captions are flattened into JPEG; sharing uses the ordinary Android/Telegram picker. */
public final class LunagramMemeEditor {
    private LunagramMemeEditor() {}
    public static AlertDialog.Builder create(Activity activity, Theme.ResourcesProvider provider, Bitmap frame, LunagramMediaAccess.Session access) {
        if (access == null || !access.canUse(LunagramFeatureCatalog.Feature.VIDEO_MEMES)) {
            return new AlertDialog.Builder(activity, provider)
                    .setTitle(LocaleController.getString(R.string.LunagramSubscriptionTitle))
                    .setMessage(LocaleController.formatString(R.string.LunagramFeatureRequiresTier, "Ultra"))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null);
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        EditTextBoldCursor top = input(activity, provider, R.string.LunagramMemeTop);
        EditTextBoldCursor bottom = input(activity, provider, R.string.LunagramMemeBottom);
        View preview = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float scale = Math.min(getWidth() / (float) frame.getWidth(), getHeight() / (float) frame.getHeight());
                canvas.save();
                canvas.translate((getWidth() - frame.getWidth() * scale) / 2f, (getHeight() - frame.getHeight() * scale) / 2f);
                canvas.scale(scale, scale);
                canvas.drawBitmap(frame, 0, 0, paint);
                drawCaption(canvas, top.getText().toString(), frame.getWidth(), frame.getHeight(), false);
                drawCaption(canvas, bottom.getText().toString(), frame.getWidth(), frame.getHeight(), true);
                canvas.restore();
            }
        };
        preview.setContentDescription(LocaleController.getString(R.string.LunagramMeme));
        content.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 190));
        content.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        content.addView(bottom, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { preview.invalidate(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        top.addTextChangedListener(watcher); bottom.addTextChangedListener(watcher);
        return new AlertDialog.Builder(activity, provider)
                .setTitle(LocaleController.getString(R.string.LunagramMeme))
                .setView(content)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.LunagramMemeShare), (dialog, which) -> {
                    if (!access.canUse(LunagramFeatureCatalog.Feature.VIDEO_MEMES)) { denied(activity); return; }
                    final String topText = top.getText().toString(), bottomText = bottom.getText().toString();
                    Utilities.globalQueue.postRunnable(() -> export(activity, frame, topText, bottomText, access));
                });
    }
    private static EditTextBoldCursor input(Activity activity, Theme.ResourcesProvider provider, int hint) {
        EditTextBoldCursor input = new EditTextBoldCursor(activity);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, provider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, provider));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, provider));
        input.setHint(LocaleController.getString(hint));
        input.setContentDescription(LocaleController.getString(hint));
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        input.setPadding(0, 0, 0, 0);
        return input;
    }
    static void drawCaption(Canvas canvas, String text, int width, int height, boolean bottom) {
        text = text.trim().toUpperCase(Locale.getDefault());
        if (text.isEmpty()) return;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        float size = Math.min(width * .075f, height * .1f);
        int padding = Math.max(8, width / 32);
        StaticLayout layout;
        do {
            paint.setTextSize(size);
            layout = new StaticLayout(text, paint, Math.max(1, width - padding * 2), Layout.Alignment.ALIGN_CENTER, 1f, 0, false);
            size *= .9f;
        } while (layout.getHeight() > height * .28f && size > 10);
        canvas.save();
        canvas.translate(padding, bottom ? height - padding - layout.getHeight() : padding);
        paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(2, paint.getTextSize() * .075f));
        layout.draw(canvas);
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
        layout.draw(canvas);
        canvas.restore();
    }
    private static void export(Activity activity, Bitmap frame, String top, String bottom, LunagramMediaAccess.Session access) {
        if (!access.canUse(LunagramFeatureCatalog.Feature.VIDEO_MEMES)) {
            AndroidUtilities.runOnUIThread(() -> denied(activity));
            return;
        }
        Bitmap image = null;
        try {
            image = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawBitmap(frame, 0, 0, null);
            drawCaption(canvas, top, image.getWidth(), image.getHeight(), false);
            drawCaption(canvas, bottom, image.getWidth(), image.getHeight(), true);
            File directory = new File(activity.getCacheDir(), "lunagram_memes");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Meme cache unavailable");
            File file = File.createTempFile("lunagram_meme_", ".jpg", directory);
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 94, output)) throw new IllegalStateException("JPEG encoding failed");
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    if (activity.isFinishing()) return;
                    if (!access.canUse(LunagramFeatureCatalog.Feature.VIDEO_MEMES)) { denied(activity); return; }
                    Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
                    Intent share = new Intent(Intent.ACTION_SEND).setType("image/jpeg");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.setClipData(ClipData.newRawUri("meme", uri));
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(share, LocaleController.getString(R.string.LunagramMemeShare)));
                } catch (Exception error) { FileLog.e(error); failure(activity); }
            });
        } catch (Exception error) { FileLog.e(error); AndroidUtilities.runOnUIThread(() -> failure(activity)); }
        finally { if (image != null) image.recycle(); }
    }
    private static void failure(Activity activity) {
        Toast.makeText(activity, LocaleController.getString(R.string.LunagramMemeSaveFailed), Toast.LENGTH_LONG).show();
    }
    private static void denied(Activity activity) {
        Toast.makeText(activity, LocaleController.formatString(R.string.LunagramFeatureRequiresTier, "Ultra"), Toast.LENGTH_SHORT).show();
    }
}
