package android.text;
/** Carries an opaque formatting token; not Android span/render implementation. */
public final class SpannedString implements Spanned {
    private final String text; public final Object spans;
    public SpannedString(CharSequence value){text=value.toString();spans=value instanceof SpannedString?((SpannedString)value).spans:null;}
    public SpannedString(String text,Object spans){this.text=text;this.spans=spans;}
    public int length(){return text.length();}public char charAt(int i){return text.charAt(i);}public CharSequence subSequence(int a,int b){return text.substring(a,b);}public String toString(){return text;}
    @SuppressWarnings("unchecked") public <T> T[] getSpans(int start,int end,Class<T> type){T[] result=(T[])java.lang.reflect.Array.newInstance(type,type.isInstance(spans)?1:0);if(result.length>0)result[0]=type.cast(spans);return result;}
    public int getSpanStart(Object span){return span==spans?0:-1;}public int getSpanEnd(Object span){return span==spans?text.length():-1;}
    @Override public boolean equals(Object value){return value instanceof SpannedString&&text.equals(value.toString())&&spans==((SpannedString)value).spans;}
}
