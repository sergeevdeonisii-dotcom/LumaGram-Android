package org.telegram.messenger;
public final class FileLog {
    public static void e(Throwable error) { throw new AssertionError(error); }
    public static void e(String error) { throw new AssertionError(error); }
}
