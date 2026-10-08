package org.telegram.tgnet;
public final class TLRPC {
    public static final int MESSAGE_FLAG_EDITED = 1 << 15;
    public static class Message {
        public int id, flags, date, edit_date;
        public String message;
    }
    public static class MessageEntity { public int offset, length; public boolean collapsed; }
    public static class TL_messageEntityBold extends MessageEntity {}
    public static class TL_messageEntityItalic extends MessageEntity {}
    public static class TL_messageEntityCode extends MessageEntity {}
    public static class TL_messageEntityPre extends MessageEntity {}
    public static class TL_messageEntityUnderline extends MessageEntity {}
    public static class TL_messageEntityStrike extends MessageEntity {}
    public static class TL_messageEntityBlockquote extends MessageEntity {}
    public static class TL_messageEntityTextUrl extends MessageEntity { public String url; }
}
