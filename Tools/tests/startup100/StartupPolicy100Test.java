package org.telegram.messenger;

public final class StartupPolicy100Test {
    private static int assertions;
    private static void equal(int expected, int actual) {
        assertions++;
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        for (int version : new int[]{0, 1, 71619, 71629, Integer.MAX_VALUE}) {
            for (int seen : new int[]{0, 1, 71619, 71629, Integer.MAX_VALUE}) {
                for (int flags = 0; flags < 16; flags++) {
                    boolean launcher = (flags & 1) != 0;
                    boolean foreground = (flags & 2) != 0;
                    boolean protectedScreen = (flags & 4) != 0;
                    boolean animations = (flags & 8) != 0;
                    int expected = version > 0 && version > seen && launcher && foreground && !protectedScreen
                            ? (animations ? LumaStartupRevealPolicy.SHOW : LumaStartupRevealPolicy.MARK_SEEN)
                            : LumaStartupRevealPolicy.DEFER;
                    equal(expected, LumaStartupRevealPolicy.decide(version, seen, launcher, foreground,
                            protectedScreen, animations));
                }
            }
        }
        int seen = 0;
        equal(LumaStartupRevealPolicy.DEFER, LumaStartupRevealPolicy.decide(10, seen, true, true, true, true));
        equal(LumaStartupRevealPolicy.SHOW, LumaStartupRevealPolicy.decide(10, seen, true, true, false, true));
        seen = 10;
        equal(LumaStartupRevealPolicy.DEFER, LumaStartupRevealPolicy.decide(10, seen, true, true, false, true));
        equal(LumaStartupRevealPolicy.SHOW, LumaStartupRevealPolicy.decide(11, seen, true, true, false, true));
        equal(LumaStartupRevealPolicy.DEFER, LumaStartupRevealPolicy.decide(9, seen, true, true, false, true));
        equal(LumaStartupRevealPolicy.MARK_SEEN, LumaStartupRevealPolicy.decide(11, seen, true, true, false, false));
        seen = 11;
        equal(LumaStartupRevealPolicy.DEFER, LumaStartupRevealPolicy.decide(11, seen, true, true, false, true));
        System.out.println("Startup policy: " + assertions + " assertions passed.");
    }
}
