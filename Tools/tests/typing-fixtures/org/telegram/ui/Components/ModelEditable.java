package org.telegram.ui.Components;
import android.text.*;
import java.lang.reflect.Array;
import java.util.*;
/** Small exclusive-span edit model. It is deliberately not an Android runtime. */
public final class ModelEditable implements Editable {
    static final class Span {Object value;int start,end,flags;Span(Object v,int s,int e,int f){value=v;start=s;end=e;flags=f;}}
    private final StringBuilder text;
    final ArrayList<Span> spans=new ArrayList<>();
    public ModelEditable(String value){text=new StringBuilder(value);}
    public int length(){return text.length();}public char charAt(int at){return text.charAt(at);}
    public CharSequence subSequence(int start,int end){return text.substring(start,end);}
    public String toString(){return text.toString();}
    public void setSpan(Object value,int start,int end,int flags){
        if(start<0||end<start||end>length())throw new IndexOutOfBoundsException();
        removeSpan(value);spans.add(new Span(value,start,end,flags));
    }
    public void removeSpan(Object value){spans.removeIf(span->span.value==value);}
    public int getSpanStart(Object value){for(Span span:spans)if(span.value==value)return span.start;return -1;}
    public int getSpanEnd(Object value){for(Span span:spans)if(span.value==value)return span.end;return -1;}
    @SuppressWarnings("unchecked")public <T>T[] getSpans(int start,int end,Class<T> type){
        ArrayList<T> found=new ArrayList<>();
        for(Span span:spans)if(type.isInstance(span.value)&&span.start<end&&span.end>start)found.add((T)span.value);
        return found.toArray((T[])Array.newInstance(type,found.size()));
    }
    void replace(int start,int end,String value){
        int added=value.length(),removed=end-start,delta=added-removed;
        Iterator<Span> it=spans.iterator();
        while(it.hasNext()){
            Span span=it.next();
            if(removed==0){
                if(span.start>=start){span.start+=added;span.end+=added;}
                else if(span.end>start)span.end+=added;
            }else if(span.start>=end){span.start+=delta;span.end+=delta;}
            else if(span.end>start){
                if(span.start>=start&&span.end<=end){it.remove();continue;}
                span.start=span.start<start?span.start:start+added;
                span.end=span.end>end?span.end+delta:start;
                if(span.end<=span.start)it.remove();
            }
        }
        text.replace(start,end,value);
    }
    public ModelEditable copyForDraft(){
        ModelEditable copy=new ModelEditable(toString());
        for(Span span:spans)if(!(span.value instanceof NoCopySpan))copy.setSpan(span.value,span.start,span.end,span.flags);
        return copy;
    }
}
