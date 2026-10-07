package android.text;
public interface Editable extends Spanned {
    void setSpan(Object span,int start,int end,int flags); void removeSpan(Object span);
}
