package android.hardware;

public final class Sensor {
    public static final int TYPE_GAME_ROTATION_VECTOR = 15, TYPE_GRAVITY = 9;
    private final int type;
    public Sensor(int type) { this.type = type; }
    public int getType() { return type; }
}
