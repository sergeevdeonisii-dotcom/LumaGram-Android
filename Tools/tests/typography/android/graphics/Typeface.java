package android.graphics;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Models face identity, style and Android-style interning, not glyph rasterization. */
public final class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
    private static final Map<String, Typeface> faces = new HashMap<>();
    public static final Typeface DEFAULT = face("sans-serif", 400, false);
    public static final Typeface SANS_SERIF = DEFAULT;
    public static final Typeface SERIF = face("serif", 400, false);
    public static final Typeface MONOSPACE = face("monospace", 400, false);
    public static final Typeface DEFAULT_BOLD = face("sans-serif", 700, false);
    public static int weightedCreateCalls;
    public static int styledCreateCalls;
    public final String family;
    public final int weight;
    private final boolean italic;

    private Typeface(String family, int weight, boolean italic) {
        this.family = family;
        this.weight = weight;
        this.italic = italic;
    }

    private static Typeface face(String family, int weight, boolean italic) {
        String key = family + ":" + weight + ":" + italic;
        Typeface result = faces.get(key);
        if (result == null) {
            result = new Typeface(family, weight, italic);
            faces.put(key, result);
        }
        return result;
    }

    public static Typeface create(String family, int style) {
        styledCreateCalls++;
        return face(family == null ? "sans-serif" : family,
                (style & BOLD) != 0 ? 700 : 400, (style & ITALIC) != 0);
    }

    public static Typeface create(Typeface family, int style) {
        return create(family == null ? null : family.family, style);
    }

    public static Typeface create(Typeface family, int weight, boolean italic) {
        weightedCreateCalls++;
        return face(family == null ? "sans-serif" : family.family, weight, italic);
    }

    public boolean isBold() { return weight >= 600; }
    public boolean isItalic() { return italic; }
    public int getStyle() { return (isBold() ? BOLD : NORMAL) | (italic ? ITALIC : NORMAL); }
    public int getWeight() { return weight; }
    public boolean equals(Object value) {
        if (!(value instanceof Typeface)) return false;
        Typeface other = (Typeface) value;
        return family.equals(other.family) && weight == other.weight && italic == other.italic;
    }
    public int hashCode() { return Objects.hash(family, weight, italic); }
    public String toString() { return family + "/" + weight + (italic ? "/italic" : ""); }
}
