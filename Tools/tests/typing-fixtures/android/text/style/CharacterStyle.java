package android.text.style;
public abstract class CharacterStyle {
    public void updateDrawState(android.text.TextPaint paint) {}
    public CharacterStyle getUnderlying() { return this; }
    public static CharacterStyle wrap(final CharacterStyle style) {
        return new CharacterStyle() {
            @Override public CharacterStyle getUnderlying() { return style.getUnderlying(); }
            @Override public void updateDrawState(android.text.TextPaint paint) { style.updateDrawState(paint); }
        };
    }
}
