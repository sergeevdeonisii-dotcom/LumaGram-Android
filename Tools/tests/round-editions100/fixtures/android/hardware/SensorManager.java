package android.hardware;

public class SensorManager {
    public int registrations;
    public int unregistrations;
    public Sensor getDefaultSensor(int type) { return new Sensor(type); }
    public boolean registerListener(SensorEventListener listener, Sensor sensor, int period) {
        registrations++;
        return true;
    }
    public void unregisterListener(SensorEventListener listener) { unregistrations++; }
    public static void getRotationMatrixFromVector(float[] matrix, float[] vector) {
        matrix[6] = vector[0]; matrix[7] = vector[1]; matrix[8] = vector[2];
    }
}
