package org.telegram.messenger;

/** Gravity-projected roll; no compass, location, frame duplication or network. */
public final class LumaHorizonState {
    private long lastTimestamp;
    private boolean initialized;
    private float roll;

    public void reset() { initialized = false; lastTimestamp = 0; roll = 0; }

    public float update(float x, float y, float z, long timestamp) {
        if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)
            || Float.isNaN(z) || Float.isInfinite(z) || timestamp <= lastTimestamp) return roll;
        double norm = Math.sqrt(x * x + y * y + z * z);
        // Looking almost straight up/down has no observable image-plane horizon.
        // Hold the last valid roll instead of spinning around a noisy singularity.
        if (norm < 0.1 || Math.hypot(x, y) / norm < 0.15) return roll;
        float target = (float) Math.toDegrees(Math.atan2(x, y));
        float delta = wrap(target - roll);
        float alpha = !initialized ? 1f : (float) (1.0 - Math.exp(-(timestamp - lastTimestamp) / 20_000_000.0));
        roll = wrap(roll + alpha * delta);
        initialized = true;
        lastTimestamp = timestamp;
        return roll;
    }

    public static float correction(float roll, int displayRotationDegrees, boolean frontCamera, boolean previewMirrored) {
        float angle = wrap(roll + displayRotationDegrees);
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
