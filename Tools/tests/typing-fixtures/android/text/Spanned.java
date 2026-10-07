package android.text;
public interface Spanned extends CharSequence {
    int SPAN_EXCLUSIVE_EXCLUSIVE=0x21, SPAN_COMPOSING=0x100;
    int getSpanStart(Object span); int getSpanEnd(Object span);
    int getSpanFlags(Object span);
    <T> T[] getSpans(int start,int end,Class<T> type);
}
