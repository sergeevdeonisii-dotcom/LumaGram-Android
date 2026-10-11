package android.graphics;
/** JVM renderer-cache identity model only; not Android native geometry or rendering. */
public final class Path {
    private final String shape;
    public Path(String shape) { this.shape = shape; }
    public String testShape() { return shape; }
}
