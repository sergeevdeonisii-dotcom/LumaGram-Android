package android.text.style;
/** IME decoration type/flags only; not Android's suggestion UI or TextPaint implementation. */
public class SuggestionSpan extends CharacterStyle implements android.text.ParcelableSpan {
    public static final int FLAG_EASY_CORRECT=1,FLAG_MISSPELLED=2,FLAG_AUTO_CORRECTION=4;
    private final int flags;
    public SuggestionSpan(int value) { flags=value; }
    public int getFlags() { return flags; }
}
