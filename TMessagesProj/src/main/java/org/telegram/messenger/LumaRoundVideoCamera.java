package org.telegram.messenger;

/** Initial facing only: camera flips and pause/resume keep their normal behavior. */
public final class LumaRoundVideoCamera {
    public static final String PREFERENCE_KEY = "luma_round_video_start_rear_camera";

    private LumaRoundVideoCamera() {}

    public static boolean isStartWithRearCameraEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREFERENCE_KEY, false);
    }

    public static void setStartWithRearCameraEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREFERENCE_KEY, enabled).apply();
    }

    public static boolean initialFrontCamera(boolean defaultFront, boolean fromPaused) {
        return initialFrontCamera(isStartWithRearCameraEnabled(), defaultFront, fromPaused);
    }

    /** A paused recording is the same video, not a new initial-camera choice. */
    public static boolean initialFrontCamera(boolean startWithRearCamera, boolean defaultFront, boolean fromPaused) {
        return fromPaused || !startWithRearCamera ? defaultFront : false;
    }

    /** Preserve the request if neither camera is present, so normal startup reports the error. */
    public static boolean availableFrontCamera(boolean requestedFront, boolean frontAvailable, boolean backAvailable) {
        if (requestedFront && !frontAvailable && backAvailable) return false;
        if (!requestedFront && !backAvailable && frontAvailable) return true;
        return requestedFront;
    }
}
