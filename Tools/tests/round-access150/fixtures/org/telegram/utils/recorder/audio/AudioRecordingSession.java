package org.telegram.utils.recorder.audio;

/** Audio setting type used by SharedSettings in the camera-only probes. */
public final class AudioRecordingSession {
    public enum NoiseSuppression {
        DISABLED, RNNOISE
    }
}
