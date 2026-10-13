package org.telegram.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SubscriptionAndroid204Test {
    private int rows, assertions;

    private void check(String label, boolean value) { assertions++; assertTrue(label, value); }
    private Context context(float fontScale, boolean rtl) {
        Configuration config = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        config.fontScale = fontScale;
        Locale locale = rtl ? new Locale("ar") : new Locale("ru");
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        Context context = RuntimeEnvironment.getApplication().createConfigurationContext(config);
        context.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        AndroidUtilities.density = context.getResources().getDisplayMetrics().density;
        LocaleController.isRTL = rtl;
        return context;
    }
    private void measure(FrameLayout cell, int widthDp, boolean rtl, String label) {
        int width = AndroidUtilities.dp(widthDp);
        cell.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        // RecyclerView rows can arrive with AT_MOST. The actual production onMeasure must
        // still fill the list width, even when both strings are very short.
        cell.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cell.layout(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight());
        check(label + ": full row width", cell.getMeasuredWidth() == width);
        check(label + ": minimum height", cell.getMeasuredHeight() >= AndroidUtilities.dp(64));
        LinearLayout text = (LinearLayout) cell.getChildAt(0);
        check(label + ": real Android widgets", text.getClass() == LinearLayout.class);
        check(label + ": text fits vertically", text.getTop() >= 0 && text.getBottom() <= cell.getMeasuredHeight() - AndroidUtilities.dp(12));
        check(label + ": text fits horizontally", text.getLeft() > 0 && text.getRight() < width);
        for (int index = 0; index < 2; index++) {
            TextView view = (TextView) text.getChildAt(index);
            check(label + ": real TextView", view.getClass() == TextView.class);
            check(label + ": text layout exists", view.getLayout() != null && view.getLineCount() > 0);
            check(label + ": no ellipsis", view.getEllipsize() == null);
            check(label + ": no glyph cutoff", view.getLayout().getHeight() <= view.getMeasuredHeight());
            for (int line = 0; line < view.getLineCount(); line++) {
                check(label + ": line not truncated", view.getLayout().getEllipsisCount(line) == 0);
                // Android getLineWidth includes a wrapping line's trailing spaces. Those
                // are not visible glyph overflow; getLineMax is the visible line advance.
                float visibleWidth = view.getLayout().getLineMax(line);
                check(label + ": line " + line + " visible width=" + visibleWidth + " within " + view.getWidth(),
                        visibleWidth <= view.getWidth() + 1);
            }
            check(label + ": last character retained", view.getLayout().getLineEnd(view.getLineCount() - 1) == view.getText().length());
        }
        rows++;
    }
    private SubscriptionCells.FeatureCell feature(Context context, String title, String description, boolean choice) {
        SubscriptionCells.FeatureCell cell = new SubscriptionCells.FeatureCell(context, null, choice);
        UItem item = choice ? SubscriptionCells.FeatureCell.ChoiceFactory.of(1, title, description)
                : SubscriptionCells.FeatureCell.Factory.of(1, 0xff9988ff, android.R.drawable.ic_dialog_info, title, description);
        if (choice) new SubscriptionCells.FeatureCell.ChoiceFactory().bindView(cell, item, true, null, null);
        else new SubscriptionCells.FeatureCell.Factory().bindView(cell, item, true, null, null);
        check("RecyclerView explicit MATCH_PARENT width", cell.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT);
        return cell;
    }

    @Test public void nativeAndroidMeasurementMatrix() {
        for (float fontScale : new float[]{1f, 1.3f, 2f}) for (boolean rtl : new boolean[]{false, true}) {
            Context context = context(fontScale, rtl);
            TextView fontProbe = new TextView(context);
            fontProbe.setTextSize(16);
            check("Native variable glyph metrics", fontProbe.getPaint().measureText("WWWW") > fontProbe.getPaint().measureText("iiii"));
            check("Font scale really applied", fontScale == 1f || fontProbe.getTextSize() > 16 * AndroidUtilities.density);
            for (int width : new int[]{240, 280, 360}) {
                String label = "width=" + width + ", font=" + fontScale + ", rtl=" + rtl;
                SubscriptionCells.FeatureCell shortRow = feature(context, "Кот", "Дома", false);
                SubscriptionCells.FeatureCell longRow = feature(context, "Расширенные настройки видеосообщений",
                        "Выбирайте качество, частоту кадров и переключение камеры во время записи видеосообщений.", false);
                measure(shortRow, width, rtl, label + " short feature");
                measure(longRow, width, rtl, label + " long feature");
                check(label + ": equal section widths", shortRow.getMeasuredWidth() == longRow.getMeasuredWidth());
                check(label + ": content-height long feature", longRow.getMeasuredHeight() > shortRow.getMeasuredHeight());
                SubscriptionCells.FeatureCell choice = feature(context, "Срок подписки", "12 месяцев · 17.43 $ или 1 260 Stars", true);
                measure(choice, width, rtl, label + " choice");
                String durationTitle = rtl ? "اشتراك لوناغرام ألترا لمدة اثني عشر شهرًا كاملة" : "Подписка на двенадцать месяцев";
                String durationPrice = rtl ? "الإجمالي 17.43 دولار أمريكي أو 1 260 نجمة" : "Итого за весь срок: 17.43 $ или 1 260 Stars";
                SubscriptionCells.PeriodChoiceCell period = new SubscriptionCells.PeriodChoiceCell(context, null, durationTitle, durationPrice, true);
                measure(period, width, rtl, label + " period");
                LinearLayout periodText = (LinearLayout) period.getChildAt(0);
                TextView title = (TextView) periodText.getChildAt(0), detail = (TextView) periodText.getChildAt(1);
                if (width == 240) {
                    check(label + ": long duration title wraps", title.getLineCount() > 1);
                    check(label + ": full total price wraps", detail.getLineCount() > 1);
                }
                View radio = period.getChildAt(1);
                check(label + ": radio separated from text", rtl ? radio.getLeft() >= periodText.getRight() : radio.getRight() <= periodText.getLeft());
                int wantedGravity = rtl ? android.view.Gravity.RIGHT : android.view.Gravity.LEFT;
                check(label + ": title gravity mirrors", (title.getGravity() & android.view.Gravity.HORIZONTAL_GRAVITY_MASK) == wantedGravity);
                check(label + ": checked radio preserved", ((RadioButton) radio).isChecked());
            }
        }
        System.out.println("PASS: " + rows + " actual extracted cells, " + assertions
                + " Android API35/native-text measurement assertions; fontScale 1/1.3/2, 240/280/360dp, LTR/RTL.");
        saveGeometryPreview();
    }

    @Test public void actualUltraFeatureCopyWraps() {
        for (int language = 0; language < FeatureLabels.VALUES.length; language++) {
            check("all thirty-four actual resource pairs", FeatureLabels.VALUES[language].length == 34);
            for (float fontScale : new float[]{1f, 2f}) for (boolean rtl : new boolean[]{false, true}) {
                Context context = context(fontScale, rtl);
                for (int width : new int[]{240, 360}) for (String[] copy : FeatureLabels.VALUES[language]) {
                    measure(feature(context, copy[0], copy[1], false), width, rtl,
                            "actual copy " + language + " / " + copy[0] + " width=" + width + " font=" + fontScale + " rtl=" + rtl);
                }
            }
        }
        System.out.println("PASS: " + rows + " actual EN/RU Ultra feature rows, " + assertions
                + " native-text assertions; 34 features, 240/360dp, fontScale 1/2, LTR/RTL.");
    }

    private void saveGeometryPreview() {
        String destination = System.getProperty("subscription.geometry.preview");
        if (destination == null || destination.isEmpty()) return;
        FrameLayout[][] columns = new FrameLayout[3][4];
        int height = 0;
        int[] widths = {360, 240, 240};
        for (int index = 0; index < columns.length; index++) {
            boolean rtl = index == 2;
            Context context = context(index == 0 ? 1f : 2f, rtl);
            columns[index][0] = feature(context, "Кот", "Дома", false);
            // A real new feature's localized resource copy, not a shorter invented specimen.
            String[] portrait = FeatureLabels.VALUES[index == 0 ? 1 : 0][22];
            columns[index][1] = feature(context, portrait[0], portrait[1], false);
            columns[index][2] = feature(context, "Срок подписки", "12 месяцев · 17.43 $ или 1 260 Stars", true);
            columns[index][3] = new SubscriptionCells.PeriodChoiceCell(context, null,
                    rtl ? "اشتراك لوناغرام ألترا لمدة اثني عشر شهرًا كاملة" : "Подписка на двенадцать месяцев",
                    rtl ? "الإجمالي 17.43 دولار أمريكي أو 1 260 نجمة" : "Итого за весь срок: 17.43 $ или 1 260 Stars", true);
            int columnHeight = 46;
            for (FrameLayout cell : columns[index]) {
                measure(cell, widths[index], rtl, "preview column=" + index);
                columnHeight += cell.getMeasuredHeight() + 16;
            }
            height = Math.max(height, columnHeight);
        }
        Bitmap bitmap = Bitmap.createBitmap(904, height + 16, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xffeeeeee);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(14);
        int left = 16;
        for (int index = 0; index < columns.length; index++) {
            LocaleController.isRTL = index == 2;
            paint.setColor(Color.BLACK);
            canvas.drawText(widths[index] + "dp / fontScale " + (index == 0 ? "1" : "2") + (index == 2 ? " / RTL" : " / LTR"), left, 25, paint);
            int top = 42;
            for (FrameLayout cell : columns[index]) {
                paint.setColor(Color.WHITE);
                canvas.drawRect(left, top, left + cell.getWidth(), top + cell.getHeight(), paint);
                int saved = canvas.save();
                canvas.translate(left, top);
                cell.draw(canvas);
                canvas.restoreToCount(saved);
                top += cell.getHeight() + 16;
            }
            left += widths[index] + 16;
        }
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(destination)) {
            assertTrue("PNG encoder succeeded", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            System.out.println("Local native-cell geometry preview (not full app): " + destination);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not save requested local geometry preview", failure);
        } finally {
            bitmap.recycle();
        }
    }

    private void measureStatus(SubscriptionCells.HeaderStatusCell cell, int widthDp, boolean rtl, String label) {
        cell.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        cell.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(widthDp - 40), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        cell.layout(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight());
        TextView visible = cell.label;
        check(label + ": visible status fits", visible.getTop() >= 0 && visible.getBottom() <= cell.getHeight());
        check(label + ": no status glyph cutoff", visible.getLayout().getHeight() <= visible.getMeasuredHeight());
        check(label + ": status not ellipsized", visible.getEllipsize() == null);
        check(label + ": complete status retained", visible.getLayout().getLineEnd(visible.getLineCount() - 1) == visible.getText().length());
        for (int line = 0; line < visible.getLineCount(); line++) {
            check(label + ": visible status line fits", visible.getLayout().getLineMax(line) <= visible.getWidth() + 1);
        }
        for (int i = 0; i < cell.getChildCount() - 1; i++) {
            View reservation = cell.getChildAt(i);
            check(label + ": reserved compact states participate in layout", reservation.getVisibility() == View.INVISIBLE);
            check(label + ": hidden state excluded from accessibility", reservation.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    @Test public void nativeStatusHeaderMatrix() {
        int cases = 0;
        for (String[] strings : StatusLabels.VALUES) for (float fontScale : new float[]{1f, 1.3f, 2f})
                for (boolean rtl : new boolean[]{false, true}) for (boolean personal : new boolean[]{false, true}) {
            Context context = context(fontScale, rtl);
            String[] states = {strings[0], strings[1], strings[personal ? 3 : 2]};
            for (int width : new int[]{240, 280, 360}) {
                String description = "header width=" + width + ", font=" + fontScale + ", rtl=" + rtl;
                SubscriptionCells.HeaderStatusCell cell = new SubscriptionCells.HeaderStatusCell(context, states);
                int compactHeight = -1;
                for (String state : states) {
                    cell.label.setText(state);
                    measureStatus(cell, width, rtl, description);
                    if (compactHeight < 0) compactHeight = cell.getHeight();
                    check(description + ": stable header across pending/active/free", compactHeight == cell.getHeight());
                    SubscriptionCells.HeaderStatusCell newlyCreated = new SubscriptionCells.HeaderStatusCell(context, states);
                    newlyCreated.label.setText(state);
                    measureStatus(newlyCreated, width, rtl, description + " first measure");
                    check(description + ": stable header on first measure, expected=" + compactHeight + ", actual=" + newlyCreated.getHeight(),
                            compactHeight == newlyCreated.getHeight());
                }
                cell.label.setText(strings[4]);
                measureStatus(cell, width, rtl, description + " failure");
                check(description + ": real failure can grow without text clipping", cell.getHeight() >= compactHeight);
                cases++;
            }
        }
        System.out.println("PASS: " + cases + " actual header-state cells; EN/RU, public/personal, 240/280/360dp, fontScale 1/1.3/2, LTR/RTL; " + assertions + " assertions.");
        saveStatusPreview();
    }

    private void saveStatusPreview() {
        String destination = System.getProperty("subscription.status.preview");
        if (destination == null || destination.isEmpty()) return;
        int[] widths = {360, 240, 360, 240};
        SubscriptionCells.HeaderStatusCell[][] columns = new SubscriptionCells.HeaderStatusCell[4][3];
        int imageWidth = 16, imageHeight = 0;
        for (int column = 0; column < 4; column++) {
            String[] strings = StatusLabels.VALUES[column / 2];
            Context context = context(column % 2 == 0 ? 1 : 2, false);
            int columnHeight = 44;
            for (int state = 0; state < 3; state++) {
                SubscriptionCells.HeaderStatusCell cell = new SubscriptionCells.HeaderStatusCell(context, strings[0], strings[1], strings[2]);
                cell.label.setText(strings[state == 2 ? 4 : state]);
                cell.label.setTextColor(state == 1 ? 0xffc4a2ff : 0xffbcbcbc);
                measureStatus(cell, widths[column], false, "header preview");
                columns[column][state] = cell;
                columnHeight += cell.getHeight() + 52;
            }
            imageWidth += widths[column] + 16;
            imageHeight = Math.max(imageHeight, columnHeight);
        }
        Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight + 16, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(14); paint.setColor(Color.WHITE);
        int left = 16;
        for (int column = 0; column < 4; column++) {
            canvas.drawText((column / 2 == 0 ? "EN" : "RU") + " " + widths[column] + "dp / font " + (column % 2 == 0 ? "1" : "2"), left, 24, paint);
            int top = 44;
            for (SubscriptionCells.HeaderStatusCell cell : columns[column]) {
                paint.setColor(0xff222222); canvas.drawRect(left, top, left + widths[column], top + cell.getHeight(), paint);
                int saved = canvas.save(); canvas.translate(left + 20, top); cell.draw(canvas); canvas.restoreToCount(saved);
                paint.setColor(0xff5c5c5c); canvas.drawRect(left, top + cell.getHeight() + 12, left + widths[column], top + cell.getHeight() + 14, paint);
                top += cell.getHeight() + 52;
            }
            left += widths[column] + 16;
        }
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(destination)) {
            assertTrue("Header PNG encoder succeeded", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not save requested header geometry preview", failure);
        } finally { bitmap.recycle(); }
    }
}
