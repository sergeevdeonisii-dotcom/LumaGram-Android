package org.telegram.messenger;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.WindowManager;

/** Owned by the round-video view. Sensors run only while its camera is open. */
public final class LumaHorizonLock implements SensorEventListener {
    private final SensorManager manager;
    private final WindowManager windows;
    private final LumaHorizonState state = new LumaHorizonState();
    private final float[] rotation = new float[9];
    private volatile boolean running;

    public LumaHorizonLock(Context context) {
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean start() {
        stop();
        if (!LumaBuildPolicy.allowsEnhancedRoundVideoStabilization() || manager == null) return false;
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (sensor == null) return false;
        try {
            // 100Hz is below Android's permission-gated 200Hz sensor rate.
            running = manager.registerListener(this, sensor, 10_000);
        } catch (RuntimeException e) {
            FileLog.e(e);
            stop();
        }
        return running;
    }

    public void stop() {
        running = false;
        if (manager != null) manager.unregisterListener(this);
        state.reset();
    }

    public boolean isRunning() { return running; }

    public float correction(boolean frontCamera, long frameTimestamp) {
        if (!running) return 0;
        int rotation = windows == null ? 0 : windows.getDefaultDisplay().getRotation() * 90;
        // Both Camera1 preview and Camera2's default MIRROR_MODE_AUTO mirror
        // front SurfaceTexture outputs; neither round-camera path overrides it.
        return LumaHorizonState.correction(state.atTimestamp(frameTimestamp), rotation, frontCamera, frontCamera);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!running || event.values.length < 3) return;
        if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values);
            state.update(rotation[6], rotation[7], rotation[8], event.timestamp);
        } else {
            state.update(event.values[0], event.values[1], event.values[2], event.timestamp);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
