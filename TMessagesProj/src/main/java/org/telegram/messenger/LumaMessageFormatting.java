package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

public final class LumaMessageFormatting {

    private static final String KEY_AUTO_BOLD = "luma_auto_bold_messages";
    private static final String KEY_AUTO_STYLE = "luma_auto_message_style";

    public static final int STYLE_NONE = 0;
    public static final int STYLE_BOLD = 1;
    public static final int STYLE_ITALIC = 2;
    public static final int STYLE_MONOSPACE = 3;
    public static final int STYLE_UNDERLINE = 4;
    public static final int STYLE_STRIKETHROUGH = 5;
    public static final int STYLE_QUOTE = 6;

    private LumaMessageFormatting() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isAutoBoldEnabled() {
        return getAutomaticStyle() == STYLE_BOLD;
    }

    public static void setAutoBoldEnabled(boolean enabled) {
        setAutomaticStyle(enabled ? STYLE_BOLD : STYLE_NONE);
    }

    public static int getAutomaticStyle() {
        final SharedPreferences preferences = preferences();
        if (!preferences.contains(KEY_AUTO_STYLE)) {
            return preferences.getBoolean(KEY_AUTO_BOLD, false) ? STYLE_BOLD : STYLE_NONE;
        }
        return clampStyle(preferences.getInt(KEY_AUTO_STYLE, STYLE_NONE));
    }

    public static void setAutomaticStyle(int style) {
        style = clampStyle(style);
        preferences().edit()
            .putInt(KEY_AUTO_STYLE, style)
            .putBoolean(KEY_AUTO_BOLD, style == STYLE_BOLD)
            .apply();
    }

    public static ArrayList<TLRPC.MessageEntity> applyAutomaticStyle(
        CharSequence text,
        ArrayList<TLRPC.MessageEntity> entities
    ) {
        final int style = getAutomaticStyle();
        if (style == STYLE_NONE || text == null || text.length() == 0) {
            return entities;
        }

        final ArrayList<TLRPC.MessageEntity> result = entities != null
            ? entities : new ArrayList<>();
        final ArrayList<Range> protectedRanges = new ArrayList<>();
        final Iterator<TLRPC.MessageEntity> iterator = result.iterator();
        while (iterator.hasNext()) {
            final TLRPC.MessageEntity entity = iterator.next();
            if (matchesStyle(entity, style)) {
                iterator.remove();
            } else if (style == STYLE_MONOSPACE
                || entity instanceof TLRPC.TL_messageEntityCode
                || entity instanceof TLRPC.TL_messageEntityPre) {
                final int start = Math.max(0, Math.min(text.length(), entity.offset));
                final int end = Math.max(start, Math.min(text.length(), entity.offset + entity.length));
                if (end > start) {
                    protectedRanges.add(new Range(start, end));
                }
            }
        }

        Collections.sort(protectedRanges, Comparator.comparingInt(range -> range.start));
        int cursor = 0;
        for (int i = 0; i < protectedRanges.size(); i++) {
            final Range range = protectedRanges.get(i);
            if (range.start > cursor) {
                addStyle(result, text, cursor, range.start, style);
            }
            cursor = Math.max(cursor, range.end);
        }
        if (cursor < text.length()) {
            addStyle(result, text, cursor, text.length(), style);
        }

        Collections.sort(result, (left, right) -> {
            final int offset = Integer.compare(left.offset, right.offset);
            return offset != 0 ? offset : Integer.compare(right.length, left.length);
        });
        return result;
    }

    private static int clampStyle(int style) {
        return style >= STYLE_NONE && style <= STYLE_QUOTE ? style : STYLE_NONE;
    }

    private static boolean matchesStyle(TLRPC.MessageEntity entity, int style) {
        switch (style) {
            case STYLE_BOLD:
                return entity instanceof TLRPC.TL_messageEntityBold;
            case STYLE_ITALIC:
                return entity instanceof TLRPC.TL_messageEntityItalic;
            case STYLE_MONOSPACE:
                return entity instanceof TLRPC.TL_messageEntityCode;
            case STYLE_UNDERLINE:
                return entity instanceof TLRPC.TL_messageEntityUnderline;
            case STYLE_STRIKETHROUGH:
                return entity instanceof TLRPC.TL_messageEntityStrike;
            case STYLE_QUOTE:
                return entity instanceof TLRPC.TL_messageEntityBlockquote;
            default:
                return false;
        }
    }

    private static TLRPC.MessageEntity createEntity(int style) {
        switch (style) {
            case STYLE_BOLD:
                return new TLRPC.TL_messageEntityBold();
            case STYLE_ITALIC:
                return new TLRPC.TL_messageEntityItalic();
            case STYLE_MONOSPACE:
                return new TLRPC.TL_messageEntityCode();
            case STYLE_UNDERLINE:
                return new TLRPC.TL_messageEntityUnderline();
            case STYLE_STRIKETHROUGH:
                return new TLRPC.TL_messageEntityStrike();
            case STYLE_QUOTE:
                return new TLRPC.TL_messageEntityBlockquote();
            default:
                return null;
        }
    }

    private static void addStyle(ArrayList<TLRPC.MessageEntity> entities, CharSequence text,
                                 int start, int end, int style) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end <= start) {
            return;
        }
        final TLRPC.MessageEntity entity = createEntity(style);
        if (entity != null) {
            entity.offset = start;
            entity.length = end - start;
            entities.add(entity);
        }
    }

    private static final class Range {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
