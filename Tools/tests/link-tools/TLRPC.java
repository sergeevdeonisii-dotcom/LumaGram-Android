package org.telegram.tgnet;
public final class TLRPC {
    public static class Chat {public boolean noforwards;}
    public static class MessageMedia {public int ttl_seconds;}
    public static class MessageAction {}
    public static class TL_messageActionEmpty extends MessageAction {}
    public static class Message {public boolean noforwards;public int ttl,ttl_period;public MessageMedia media;public MessageAction action;public Object rich_message;}
}
