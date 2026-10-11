package org.telegram.tgnet;

public final class TLRPC {
    public static class User { public long id; public UserStatus status; }
    public static class UserStatus { public int expires; public boolean by_me; }
    public static class TL_userStatusOnline extends UserStatus { }
    public static class TL_userStatusOffline extends UserStatus { }
    public static class TL_userStatusRecently extends UserStatus { }
    public static class TL_userStatusLastWeek extends UserStatus { }
    public static class TL_userStatusLastMonth extends UserStatus { }
    public static class TL_userStatusHidden extends UserStatus { }
    public static class TL_userStatusEmpty extends UserStatus { }
}
