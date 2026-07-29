package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Draws a printable, Telegram-like chat history directly into an Android PDF. */
public final class LumaChatPdfRenderer {

    public interface CancellationChecker {
        void check() throws Exception;
    }

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final float PAGE_MARGIN = 36f;
    private static final float FOOTER_TOP = 807f;
    private static final float MAX_BUBBLE_WIDTH = 420f;
    private static final float MIN_BUBBLE_WIDTH = 150f;
    private static final float BUBBLE_PADDING = 12f;
    private static final float MESSAGE_GAP = 8f;

    private LumaChatPdfRenderer() {
    }

    public static void render(File target, String title, List<File> pageFiles,
                              File sessionDir, CancellationChecker checker) throws Exception {
        Renderer renderer = new Renderer(target, title, pageFiles, sessionDir, checker);
        renderer.render();
    }

    private static final class Renderer {
        private final File target;
        private final String title;
        private final List<File> pageFiles;
        private final File sessionDir;
        private final CancellationChecker checker;
        private final PdfDocument document = new PdfDocument();

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = textPaint(21f, Color.WHITE, Typeface.BOLD);
        private final TextPaint subtitlePaint = textPaint(10f, 0xffddd4ff, Typeface.NORMAL);
        private final TextPaint pageTitlePaint = textPaint(10f, 0xff514a68, Typeface.BOLD);
        private final TextPaint senderPaint = textPaint(10.5f, 0xff6750a4, Typeface.BOLD);
        private final TextPaint timePaint = textPaint(8.5f, 0xff777486, Typeface.NORMAL);
        private final TextPaint bodyPaint = textPaint(11.5f, 0xff24222a, Typeface.NORMAL);
        private final TextPaint attachmentPaint = textPaint(9.5f, 0xff51456f, Typeface.BOLD);
        private final TextPaint datePaint = textPaint(9f, 0xff5e596b, Typeface.BOLD);
        private final TextPaint footerPaint = textPaint(8f, 0xff8c8798, Typeface.NORMAL);

        private PdfDocument.Page page;
        private Canvas canvas;
        private int pageNumber;
        private float y;
        private float contentTop;
        private String lastDateKey;

        Renderer(File target, String title, List<File> pageFiles,
                 File sessionDir, CancellationChecker checker) {
            this.target = target;
            this.title = TextUtils.isEmpty(title) ? localized("Чат", "Chat") : title;
            this.pageFiles = pageFiles == null ? new ArrayList<>() : pageFiles;
            this.sessionDir = sessionDir;
            this.checker = checker;
            linePaint.setStrokeWidth(1f);
        }

        void render() throws Exception {
            try {
                startPage(true);
                for (int i = pageFiles.size() - 1; i >= 0; i--) {
                    checker.check();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new FileInputStream(pageFiles.get(i)), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            checker.check();
                            drawMessage(new JSONObject(line));
                        }
                    }
                }
                if (pageFiles.isEmpty()) {
                    drawEmptyState();
                }
                finishPage();
                try (FileOutputStream output = new FileOutputStream(target)) {
                    document.writeTo(output);
                }
            } finally {
                if (page != null) {
                    try {
                        document.finishPage(page);
                    } catch (Throwable ignore) {
                    }
                    page = null;
                }
                document.close();
            }
        }

        private void startPage(boolean first) {
            finishPage();
            pageNumber++;
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
            page = document.startPage(info);
            canvas = page.getCanvas();
            canvas.drawColor(0xfff7f6fb);
            if (first) {
                fillPaint.setColor(0xff6750a4);
                canvas.drawRoundRect(new RectF(30f, 28f, PAGE_WIDTH - 30f, 108f), 20f, 20f, fillPaint);
                CharSequence heading = TextUtils.ellipsize(title, titlePaint, PAGE_WIDTH - 110f, TextUtils.TruncateAt.END);
                canvas.drawText(heading.toString(), 48f, 63f, titlePaint);
                String exported = localized("Экспорт чата", "Chat export") + " - "
                        + new SimpleDateFormat("dd.MM.yyyy HH:mm", locale()).format(new Date());
                canvas.drawText(exported, 48f, 86f, subtitlePaint);
                contentTop = 126f;
            } else {
                CharSequence heading = TextUtils.ellipsize(title, pageTitlePaint, PAGE_WIDTH - 140f, TextUtils.TruncateAt.END);
                canvas.drawText(heading.toString(), PAGE_MARGIN, 34f, pageTitlePaint);
                String pageLabel = localized("Страница ", "Page ") + pageNumber;
                canvas.drawText(pageLabel, PAGE_WIDTH - PAGE_MARGIN - pageTitlePaint.measureText(pageLabel), 34f, pageTitlePaint);
                linePaint.setColor(0xffddd9e6);
                canvas.drawLine(PAGE_MARGIN, 45f, PAGE_WIDTH - PAGE_MARGIN, 45f, linePaint);
                contentTop = 59f;
            }
            y = contentTop;
        }

        private void finishPage() {
            if (page == null) {
                return;
            }
            linePaint.setColor(0xffddd9e6);
            canvas.drawLine(PAGE_MARGIN, 818f, PAGE_WIDTH - PAGE_MARGIN, 818f, linePaint);
            String footer = "LumaGram - " + localized("экспорт переписки", "chat export");
            canvas.drawText(footer, PAGE_MARGIN, 832f, footerPaint);
            String number = String.valueOf(pageNumber);
            canvas.drawText(number, PAGE_WIDTH - PAGE_MARGIN - footerPaint.measureText(number), 832f, footerPaint);
            document.finishPage(page);
            page = null;
            canvas = null;
        }

        private void drawEmptyState() {
            fillPaint.setColor(0xffffffff);
            canvas.drawRoundRect(new RectF(75f, y + 70f, PAGE_WIDTH - 75f, y + 170f), 18f, 18f, fillPaint);
            TextPaint emptyPaint = textPaint(14f, 0xff777486, Typeface.NORMAL);
            String empty = localized("В истории чата пока нет сообщений", "There are no messages in this chat yet");
            float x = (PAGE_WIDTH - emptyPaint.measureText(empty)) / 2f;
            canvas.drawText(empty, Math.max(PAGE_MARGIN, x), y + 127f, emptyPaint);
        }

        private void drawMessage(JSONObject item) throws Exception {
            long unixTime = parseLong(item.optString("date_unixtime"));
            Date date = unixTime > 0 ? new Date(unixTime * 1000L) : new Date();
            String dateKey = new SimpleDateFormat("yyyyMMdd", Locale.US).format(date);
            if (!dateKey.equals(lastDateKey)) {
                ensureSpace(80f);
                drawDateSeparator(new SimpleDateFormat("dd MMMM yyyy", locale()).format(date));
                lastDateKey = dateKey;
            }

            String text = item.optString("text", "");
            String attachment = attachmentLabel(item);
            File imageFile = imageFile(item);
            if (TextUtils.isEmpty(text) && TextUtils.isEmpty(attachment)) {
                text = typeLabel(item.optString("type"));
            }
            String sender = item.optString("from", localized("Неизвестный отправитель", "Unknown sender"));
            String time = new SimpleDateFormat("HH:mm", locale()).format(date);
            boolean outgoing = item.optBoolean("out");

            String remaining = text;
            boolean firstChunk = true;
            boolean attachmentPending = !TextUtils.isEmpty(attachment) || imageFile != null;
            do {
                checker.check();
                BubbleLayout full = createBubble(sender, time, remaining, attachment,
                        imageFile, firstChunk, attachmentPending);
                float available = FOOTER_TOP - y;
                if (full.height <= available) {
                    drawBubble(full, outgoing);
                    return;
                }
                if (y > contentTop + 1f) {
                    startPage(false);
                    continue;
                }

                BubbleLayout textOnly = createBubble(sender, time, remaining, attachment,
                        null, firstChunk, false);
                if (!TextUtils.isEmpty(remaining) && textOnly.textLayout != null) {
                    float fixedHeight = textOnly.height - textOnly.textLayout.getHeight();
                    float allowedTextHeight = Math.max(bodyPaint.getTextSize() * 1.5f, available - fixedHeight);
                    int fittingLines = 0;
                    for (int line = 0; line < textOnly.textLayout.getLineCount(); line++) {
                        if (textOnly.textLayout.getLineBottom(line) <= allowedTextHeight) {
                            fittingLines = line + 1;
                        } else {
                            break;
                        }
                    }
                    if (fittingLines > 0 && fittingLines < textOnly.textLayout.getLineCount()) {
                        int end = textOnly.textLayout.getLineEnd(fittingLines - 1);
                        String part = remaining.substring(0, Math.max(1, Math.min(end, remaining.length())));
                        BubbleLayout partial = createBubble(sender, time, part, attachment,
                                null, firstChunk, false);
                        drawBubble(partial, outgoing);
                        remaining = remaining.substring(part.length());
                        firstChunk = false;
                        startPage(false);
                        continue;
                    }
                }

                if (!TextUtils.isEmpty(remaining) && attachmentPending && textOnly.height <= available) {
                    drawBubble(textOnly, outgoing);
                    remaining = "";
                    firstChunk = false;
                    startPage(false);
                    continue;
                }

                drawBubble(full, outgoing);
                return;
            } while (true);
        }

        private void drawDateSeparator(String label) {
            float width = Math.min(230f, datePaint.measureText(label) + 26f);
            float left = (PAGE_WIDTH - width) / 2f;
            fillPaint.setColor(0xffe7e3ee);
            canvas.drawRoundRect(new RectF(left, y, left + width, y + 23f), 11.5f, 11.5f, fillPaint);
            canvas.drawText(label, left + (width - datePaint.measureText(label)) / 2f, y + 15.5f, datePaint);
            y += 32f;
        }

        private BubbleLayout createBubble(String sender, String time, String text,
                                          String attachment, File imageFile,
                                          boolean firstChunk, boolean includeAttachment) {
            String senderLabel = firstChunk ? sender : sender + localized(" - продолжение", " - continued");
            float innerMax = MAX_BUBBLE_WIDTH - BUBBLE_PADDING * 2f;
            CharSequence body = emoji(text);
            StaticLayout provisional = TextUtils.isEmpty(body) ? null : layout(body, bodyPaint, Math.round(innerMax));
            float measured = Math.max(116f, senderPaint.measureText(senderLabel) + timePaint.measureText(time) + 22f);
            if (provisional != null) {
                for (int line = 0; line < provisional.getLineCount(); line++) {
                    measured = Math.max(measured, provisional.getLineWidth(line));
                }
            }
            if (includeAttachment) {
                measured = Math.max(measured, Math.min(innerMax, attachmentPaint.measureText(attachment) + 32f));
                if (imageFile != null) {
                    measured = Math.max(measured, 250f);
                }
            }
            int innerWidth = Math.round(Math.max(MIN_BUBBLE_WIDTH - BUBBLE_PADDING * 2f,
                    Math.min(innerMax, measured)));
            StaticLayout textLayout = TextUtils.isEmpty(body) ? null : layout(body, bodyPaint, innerWidth);
            float height = BUBBLE_PADDING + 14f;
            if (textLayout != null) {
                height += 6f + textLayout.getHeight();
            }
            float imageHeight = 0f;
            if (includeAttachment) {
                if (imageFile != null) {
                    imageHeight = imageHeight(imageFile, innerWidth);
                    height += 8f + imageHeight + 21f;
                } else if (!TextUtils.isEmpty(attachment)) {
                    height += 8f + 28f;
                }
            }
            height += BUBBLE_PADDING;
            return new BubbleLayout(senderLabel, time, attachment, imageFile,
                    innerWidth + BUBBLE_PADDING * 2f, height, textLayout,
                    innerWidth, imageHeight, includeAttachment);
        }

        private void drawBubble(BubbleLayout bubble, boolean outgoing) {
            float left = outgoing ? PAGE_WIDTH - PAGE_MARGIN - bubble.width : PAGE_MARGIN;
            RectF rect = new RectF(left, y, left + bubble.width, y + bubble.height);
            fillPaint.setColor(0x12000000);
            canvas.drawRoundRect(new RectF(rect.left, rect.top + 2f, rect.right, rect.bottom + 2f), 15f, 15f, fillPaint);
            fillPaint.setColor(outgoing ? 0xffe7ddfa : 0xffffffff);
            canvas.drawRoundRect(rect, 15f, 15f, fillPaint);

            float contentLeft = left + BUBBLE_PADDING;
            float currentY = y + BUBBLE_PADDING;
            senderPaint.setColor(outgoing ? 0xff5e3d99 : 0xff336a9c);
            CharSequence sender = TextUtils.ellipsize(bubble.sender, senderPaint,
                    bubble.innerWidth - timePaint.measureText(bubble.time) - 14f, TextUtils.TruncateAt.END);
            canvas.drawText(sender.toString(), contentLeft, currentY + 10f, senderPaint);
            canvas.drawText(bubble.time,
                    left + bubble.width - BUBBLE_PADDING - timePaint.measureText(bubble.time),
                    currentY + 10f, timePaint);
            currentY += 14f;

            if (bubble.textLayout != null) {
                currentY += 6f;
                canvas.save();
                canvas.translate(contentLeft, currentY);
                bubble.textLayout.draw(canvas);
                canvas.restore();
                currentY += bubble.textLayout.getHeight();
            }

            if (bubble.includeAttachment) {
                if (bubble.imageFile != null) {
                    currentY += 8f;
                    RectF imageRect = new RectF(contentLeft, currentY,
                            contentLeft + bubble.innerWidth, currentY + bubble.imageHeight);
                    drawImage(bubble.imageFile, imageRect);
                    currentY += bubble.imageHeight + 5f;
                    drawAttachmentLabel(bubble.attachment, contentLeft, currentY,
                            bubble.innerWidth, outgoing, false);
                } else if (!TextUtils.isEmpty(bubble.attachment)) {
                    currentY += 8f;
                    drawAttachmentLabel(bubble.attachment, contentLeft, currentY,
                            bubble.innerWidth, outgoing, true);
                }
            }
            y += bubble.height + MESSAGE_GAP;
        }

        private void drawAttachmentLabel(String label, float left, float top,
                                         float width, boolean outgoing, boolean pill) {
            if (TextUtils.isEmpty(label)) {
                return;
            }
            if (pill) {
                fillPaint.setColor(outgoing ? 0xffd9c9f4 : 0xfff0eef4);
                canvas.drawRoundRect(new RectF(left, top, left + width, top + 28f), 10f, 10f, fillPaint);
                left += 10f;
                top += 18f;
                width -= 20f;
            } else {
                top += 11f;
            }
            CharSequence shortened = TextUtils.ellipsize(label, attachmentPaint, width,
                    TextUtils.TruncateAt.MIDDLE);
            canvas.drawText(shortened.toString(), left, top, attachmentPaint);
        }

        private void drawImage(File file, RectF destination) {
            Bitmap bitmap = decodeSampled(file, Math.round(destination.width() * 2f),
                    Math.round(destination.height() * 2f));
            if (bitmap == null) {
                fillPaint.setColor(0xffebe8f0);
                canvas.drawRoundRect(destination, 11f, 11f, fillPaint);
                String label = localized("Фото недоступно", "Photo unavailable");
                canvas.drawText(label, destination.left + 12f,
                        destination.centerY() + 4f, timePaint);
                return;
            }
            Rect source = centerCrop(bitmap.getWidth(), bitmap.getHeight(), destination.width(), destination.height());
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(destination, 11f, 11f, Path.Direction.CW);
            canvas.clipPath(clip);
            fillPaint.setFilterBitmap(true);
            canvas.drawBitmap(bitmap, source, destination, fillPaint);
            canvas.restore();
            bitmap.recycle();
        }

        private void ensureSpace(float height) {
            if (y + height > FOOTER_TOP) {
                startPage(false);
            }
        }

        private File imageFile(JSONObject item) {
            String media = item.optString("media");
            String mime = item.optString("mime_type");
            if (TextUtils.isEmpty(media) || !mime.startsWith("image/") || sessionDir == null) {
                return null;
            }
            File file = new File(sessionDir, media);
            return file.exists() ? file : null;
        }

        private String attachmentLabel(JSONObject item) {
            String name = item.optString("media_original_name");
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
            String type = item.optString("type");
            return "message".equals(type) || "service".equals(type) ? "" : typeLabel(type);
        }
    }

    private static final class BubbleLayout {
        final String sender;
        final String time;
        final String attachment;
        final File imageFile;
        final float width;
        final float height;
        final StaticLayout textLayout;
        final int innerWidth;
        final float imageHeight;
        final boolean includeAttachment;

        BubbleLayout(String sender, String time, String attachment, File imageFile,
                     float width, float height, StaticLayout textLayout, int innerWidth,
                     float imageHeight, boolean includeAttachment) {
            this.sender = sender;
            this.time = time;
            this.attachment = attachment;
            this.imageFile = imageFile;
            this.width = width;
            this.height = height;
            this.textLayout = textLayout;
            this.innerWidth = innerWidth;
            this.imageHeight = imageHeight;
            this.includeAttachment = includeAttachment;
        }
    }

    private static TextPaint textPaint(float size, int color, int style) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(Typeface.create("sans-serif", style));
        return paint;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        return new StaticLayout(text, paint, Math.max(1, width), Layout.Alignment.ALIGN_NORMAL,
                1.08f, 1.5f, false);
    }

    private static CharSequence emoji(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        try {
            return Emoji.replaceEmoji(text, bodyFontMetrics(), false);
        } catch (Throwable ignore) {
            return text;
        }
    }

    private static Paint.FontMetricsInt bodyFontMetrics() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(11.5f);
        return paint.getFontMetricsInt();
    }

    private static float imageHeight(File file, int width) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return 120f;
        }
        float natural = width * bounds.outHeight / (float) bounds.outWidth;
        return Math.max(90f, Math.min(190f, natural));
    }

    private static Bitmap decodeSampled(File file, int targetWidth, int targetHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth
                    && bounds.outHeight / (sample * 2) >= targetHeight) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Rect centerCrop(int sourceWidth, int sourceHeight,
                                   float targetWidth, float targetHeight) {
        float sourceRatio = sourceWidth / (float) sourceHeight;
        float targetRatio = targetWidth / targetHeight;
        if (sourceRatio > targetRatio) {
            int cropWidth = Math.max(1, Math.round(sourceHeight * targetRatio));
            int left = (sourceWidth - cropWidth) / 2;
            return new Rect(left, 0, left + cropWidth, sourceHeight);
        }
        int cropHeight = Math.max(1, Math.round(sourceWidth / targetRatio));
        int top = (sourceHeight - cropHeight) / 2;
        return new Rect(0, top, sourceWidth, top + cropHeight);
    }

    private static String typeLabel(String type) {
        if ("photo".equals(type)) return localized("Фотография", "Photo");
        if ("voice_message".equals(type)) return localized("Голосовое сообщение", "Voice message");
        if ("video_message".equals(type)) return localized("Видеосообщение", "Video message");
        if ("video_file".equals(type)) return localized("Видео", "Video");
        if ("audio_file".equals(type)) return localized("Аудиофайл", "Audio file");
        if ("animation".equals(type)) return "GIF";
        if ("sticker".equals(type)) return localized("Стикер", "Sticker");
        if ("file".equals(type)) return localized("Файл", "File");
        if ("service".equals(type)) return localized("Служебное сообщение", "Service message");
        return localized("Сообщение", "Message");
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    private static Locale locale() {
        Locale current = LocaleController.getInstance().getCurrentLocale();
        return current == null ? Locale.getDefault() : current;
    }

    private static String localized(String russian, String english) {
        return "ru".equalsIgnoreCase(locale().getLanguage()) ? russian : english;
    }
}
