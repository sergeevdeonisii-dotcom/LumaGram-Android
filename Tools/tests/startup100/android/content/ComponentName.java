package android.content;
public final class ComponentName {
    private final String value;
    public ComponentName(String packageName, String name) { value = packageName + "/" + name; }
    @Override public boolean equals(Object other) { return other instanceof ComponentName && value.equals(((ComponentName) other).value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return value; }
}
