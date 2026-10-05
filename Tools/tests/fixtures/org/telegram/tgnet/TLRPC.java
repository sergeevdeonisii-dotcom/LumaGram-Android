package org.telegram.tgnet;

/** Minimal entity data, not Telegram transport or serialization. */
public class TLRPC {
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
