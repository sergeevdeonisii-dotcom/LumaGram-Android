package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

public final class LumaMessageFormatting {

    private static final String KEY_AUTO_BOLD = "luma_auto_bold_messages";

    private LumaMessageFormatting() {
    }

    private static SharedPreferences preferences() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isAutoBoldEnabled() {
        return preferences().getBoolean(KEY_AUTO_BOLD, false);
    }

    public static void setAutoBoldEnabled(boolean enabled) {
        preferences().edit().putBoolean(KEY_AUTO_BOLD, enabled).apply();
    }

    public static ArrayList<TLRPC.MessageEntity> applyAutoBold(
        CharSequence text,
        ArrayList<TLRPC.MessageEntity> entities
    ) {
        if (!isAutoBoldEnabled() || text == null || text.length() == 0) {
            return entities;
        }

        final ArrayList<TLRPC.MessageEntity> result = entities != null
            ? entities : new ArrayList<>();
        final ArrayList<Range> protectedRanges = new ArrayList<>();
        final Iterator<TLRPC.MessageEntity> iterator = result.iterator();
        while (iterator.hasNext()) {
            final TLRPC.MessageEntity entity = iterator.next();
            if (entity instanceof TLRPC.TL_messageEntityBold) {
                iterator.remove();
            } else if (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre) {
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
                addBold(result, text, cursor, range.start);
            }
            cursor = Math.max(cursor, range.end);
        }
        if (cursor < text.length()) {
            addBold(result, text, cursor, text.length());
        }

        Collections.sort(result, (left, right) -> {
            final int offset = Integer.compare(left.offset, right.offset);
            return offset != 0 ? offset : Integer.compare(right.length, left.length);
        });
        return result;
    }

    private static void addBold(ArrayList<TLRPC.MessageEntity> entities, CharSequence text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (end <= start) {
            return;
        }
        final TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
        bold.offset = start;
        bold.length = end - start;
        entities.add(bold);
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
