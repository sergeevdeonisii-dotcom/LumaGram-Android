package org.telegram.messenger;
public final class AndroidUtilities {
    public static float density = 1f;
    public static int dp(float value) { return (int) Math.ceil(value * density); }
}
