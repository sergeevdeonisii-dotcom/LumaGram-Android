package org.telegram.messenger;

/** Gravity-projected roll; no compass, location, frame duplication or network. */
public final class LumaHorizonState {
    private long lastTimestamp;
    private boolean initialized;
    private float roll;
    private final long[] timestamps = new long[256];
    private final float[] angles = new float[256];
    private int next, count;

    public synchronized void reset() { initialized = false; lastTimestamp = 0; roll = 0; next = count = 0; }

    public synchronized float update(float x, float y, float z, long timestamp) {
        if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)
            || Float.isNaN(z) || Float.isInfinite(z) || timestamp <= lastTimestamp) return roll;
        double norm = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        // Looking almost straight up/down has no observable image-plane horizon.
        // Hold the last valid roll instead of spinning around a noisy singularity.
        if (norm < 0.1 || Math.hypot(x, y) / norm < 0.15) return roll;
        float target = (float) Math.toDegrees(Math.atan2(x, y));
        float delta = wrap(target - roll);
        float alpha = !initialized ? 1f : (float) (1.0 - Math.exp(-(timestamp - lastTimestamp) / 20_000_000.0));
        roll = wrap(roll + alpha * delta);
        initialized = true;
        lastTimestamp = timestamp;
        timestamps[next] = timestamp;
        angles[next] = roll;
        next = (next + 1) % timestamps.length;
        count = Math.min(count + 1, timestamps.length);
        return roll;
    }

    /** Camera2 REALTIME timestamps share the sensor timebase. Interpolate the
     * frame's pose, not the newer phone pose when a buffered frame reaches GL. */
    public synchronized float atTimestamp(long timestamp) {
        if (timestamp <= 0 || count == 0 || timestamp >= lastTimestamp) return roll;
        int previous = (next - count + timestamps.length) % timestamps.length;
        if (timestamp <= timestamps[previous]) return angles[previous];
        for (int i = 1; i < count; i++) {
            int current = (previous + 1) % timestamps.length;
            if (timestamp <= timestamps[current]) {
                float fraction = (float) ((double) (timestamp - timestamps[previous]) / (timestamps[current] - timestamps[previous]));
                return wrap(angles[previous] + fraction * wrap(angles[current] - angles[previous]));
            }
            previous = current;
        }
        return roll;
    }

    public static float correction(float roll, int displayRotationDegrees, boolean frontCamera, boolean previewMirrored) {
        // Display.getRotation() reports the graphics compensation, opposite
        // to physical rotation: a +90 degree sensor roll at ROTATION_90 is level.
        float angle = wrap(roll - displayRotationDegrees);
        // A front lens reverses the optical viewing direction; mirroring its
        // preview reverses it again. Android's default mirrored front preview
        // therefore uses the same roll sign as a normal rear preview.
        return frontCamera != previewMirrored ? -angle : angle;
    }

    public static float wrap(float angle) {
        angle %= 360f;
        if (angle > 180f) angle -= 360f;
        if (angle < -180f) angle += 360f;
        return angle;
    }

    /** Column-major base * rotation * constant crop, shared by preview and MP4. */
    public static float[] transform(float[] base, float angle) {
        float[] result = base.clone();
        double radians = Math.toRadians(angle);
        float c = (float) Math.cos(radians) * 1.414214f;
        float s = (float) Math.sin(radians) * 1.414214f;
        for (int row = 0; row < 4; row++) {
            result[row] = base[row] * c + base[4 + row] * s;
            result[4 + row] = base[4 + row] * c - base[row] * s;
        }
        return result;
    }
}
