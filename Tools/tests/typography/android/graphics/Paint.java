package android.graphics;

public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    private Typeface typeface;
    private final int flags;
    public Paint() { this(0); }
    public Paint(int flags) { this.flags = flags; }
    public Typeface getTypeface() { return typeface; }
    public Typeface setTypeface(Typeface next) {
        Typeface previous = typeface;
        typeface = next;
        return previous;
    }
    public int getFlags() { return flags; }
}
