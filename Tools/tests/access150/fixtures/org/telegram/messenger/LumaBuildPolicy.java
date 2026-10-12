package org.telegram.messenger;
public final class LumaBuildPolicy {
    public static boolean personal,privacy=true;
    public static boolean isPersonalEdition() { return personal; }
    public static boolean allowsPrivacyTools() { return privacy; }
}
