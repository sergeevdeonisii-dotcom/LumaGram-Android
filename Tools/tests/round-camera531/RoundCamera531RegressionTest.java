package org.telegram.messenger;

/** Actual preference/facing helper tested without opening Android cameras. */
public final class RoundCamera531RegressionTest {
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        MessagesController.values.clear();
        check(!LumaRoundVideoCamera.isStartWithRearCameraEnabled(), "Default stays disabled");
        check(LumaRoundVideoCamera.initialFrontCamera(true, false), "Disabled preserves default front");
        check(!LumaRoundVideoCamera.initialFrontCamera(false, false), "Disabled preserves remembered rear");
        LumaRoundVideoCamera.setStartWithRearCameraEnabled(true);
        check(Boolean.TRUE.equals(MessagesController.values.get(LumaRoundVideoCamera.PREFERENCE_KEY)), "Enabled written to global preferences");
        check(LumaRoundVideoCamera.isStartWithRearCameraEnabled(), "Setting read back without cached instance state");
        check(!LumaRoundVideoCamera.initialFrontCamera(true, false), "Enabled new recording starts rear");
        check(!LumaRoundVideoCamera.initialFrontCamera(false, false), "Enabled stays rear when already rear");
        check(LumaRoundVideoCamera.initialFrontCamera(true, true), "Resumed front segment keeps front");
        check(!LumaRoundVideoCamera.initialFrontCamera(false, true), "Resumed rear segment keeps rear");
        boolean manuallyFlippedFront = true;
        check(!LumaRoundVideoCamera.initialFrontCamera(manuallyFlippedFront, false), "Next new recording resets manually flipped front to rear");
        LumaRoundVideoCamera.setStartWithRearCameraEnabled(false);
        check(!LumaRoundVideoCamera.isStartWithRearCameraEnabled(), "Disabled stored and restored");
        check(LumaRoundVideoCamera.initialFrontCamera(true, false), "Disabling restores standard initial choice");
        for (boolean requestedFront : new boolean[] {false, true}) {
            for (boolean frontAvailable : new boolean[] {false, true}) {
                for (boolean backAvailable : new boolean[] {false, true}) {
                    boolean actual = LumaRoundVideoCamera.availableFrontCamera(requestedFront, frontAvailable, backAvailable);
                    boolean expected = frontAvailable == backAvailable ? requestedFront : frontAvailable;
                    check(actual == expected, "Availability fallback combination");
                }
            }
        }
        for (boolean enabled : new boolean[] {false, true}) {
            for (boolean defaultFront : new boolean[] {false, true}) {
                for (boolean fromPaused : new boolean[] {false, true}) {
                    check(LumaRoundVideoCamera.initialFrontCamera(enabled, defaultFront, fromPaused)
                                    == (!(enabled && !fromPaused) && defaultFront),
                            "All start/pause/preference combinations");
                }
            }
        }
        System.out.println("PASS: " + assertions + " rear-camera preference/facing assertions. Device camera startup is not exercised.");
    }
}
