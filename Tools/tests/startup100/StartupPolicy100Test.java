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
                            ? (animations ? LumaStartupRevealPolicy.SHOW : LumaStartupRevealPolicy.SHOW_STATIC)
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
        equal(LumaStartupRevealPolicy.SHOW_STATIC, LumaStartupRevealPolicy.decide(11, seen, true, true, false, false));
        seen = 11;
        equal(LumaStartupRevealPolicy.DEFER, LumaStartupRevealPolicy.decide(11, seen, true, true, false, true));
        for (int flags = 0; flags < 32; flags++) {
            equal(flags == 31 ? 1 : 0, LumaStartupRevealPolicy.canMarkSeen((flags & 1) != 0,
                    (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0) ? 1 : 0);
        }
        equal(1, LumaStartupRevealPolicy.hasRelease15Notes("1.5") ? 1 : 0);
        for (String name : new String[]{null, "", "1.0.5", "2.0", "2.5", "3.0", "1.5-debug"}) {
            equal(0, LumaStartupRevealPolicy.hasRelease15Notes(name) ? 1 : 0);
        }
        equal(1, LumaStartupRevealPolicy.hasRelease20Notes("2.0") ? 1 : 0);
        for (String name : new String[]{null, "", "1.5", "2.0.1", "2.1", "3.0", "2.0-debug"}) {
            equal(0, LumaStartupRevealPolicy.hasRelease20Notes(name) ? 1 : 0);
        }
        // Installed-code transitions, not hardcoded visible labels, drive each update's presentation.
        seen = 0;
        for (int installed : new int[]{71679, 71689, 71699, 71709, 71809}) {
            equal(LumaStartupRevealPolicy.SHOW_STATIC, LumaStartupRevealPolicy.decide(installed, seen, true, true, false, false));
            // A failed/unattached show must leave the version eligible.
            equal(0, LumaStartupRevealPolicy.canMarkSeen(true, false, true, true, true) ? 1 : 0);
            equal(LumaStartupRevealPolicy.SHOW, LumaStartupRevealPolicy.decide(installed, seen, true, true, false, true));
            if (LumaStartupRevealPolicy.canMarkSeen(true, true, true, true, true)) seen = installed;
            equal(LumaStartupRevealPolicy.DEFER, LumaStartupRevealPolicy.decide(installed, seen, true, true, false, true));
        }
        System.out.println("Startup policy: " + assertions + " assertions passed.");
    }
}
