package org.telegram.ui.Components;
import android.text.*;
import java.util.*;
public class EditTextBoldCursor {
    public ModelEditable text;
    public Layout layout;
    public boolean attached=true,shown=true;
    public int scrollX,scrollY,width=200,height=40,paddingLeft=4,compoundPaddingLeft=4,extendedTop=3,totalTop=3,gravity=48;
    public int selectionStart,selectionEnd,invalidates,animationInvalidates;
    public Object transformation;
    public final TextPaint paint=new TextPaint(1);
    public final ArrayList<TextWatcher> watchers=new ArrayList<>();
    public EditTextBoldCursor(String value){text=new ModelEditable(value);layout=new Layout(text);selectionStart=selectionEnd=value.length();}
    public Editable getText(){return text;}
    public void addTextChangedListener(TextWatcher watcher){watchers.add(watcher);}
    public void removeTextChangedListener(TextWatcher watcher){watchers.remove(watcher);}
    public Layout getLayout(){return layout;}public TextPaint getPaint(){return paint;}
    public int getPaddingLeft(){return paddingLeft;}public int getCompoundPaddingLeft(){return compoundPaddingLeft;}
    public int getScrollX(){return scrollX;}public int getScrollY(){return scrollY;}
    public int getWidth(){return width;}public int getHeight(){return height;}
    public int getExtendedPaddingTop(){return extendedTop;}public int getTotalPaddingTop(){return totalTop;}
    public int getGravity(){return gravity;}public float getTextSize(){return 10;}
    public Object getTransformationMethod(){return transformation;}
    public boolean isAttachedToWindow(){return attached;}public boolean isShown(){return shown;}
    public void invalidate(){invalidates++;}public void postInvalidateOnAnimation(){animationInvalidates++;}
    public void replace(int start,int end,String value){
        ArrayList<TextWatcher> current=new ArrayList<>(watchers);
        for(TextWatcher watcher:current)watcher.beforeTextChanged(text,start,end-start,value.length());
        text.replace(start,end,value);selectionStart=selectionEnd=start+value.length();
        for(TextWatcher watcher:current)watcher.onTextChanged(text,start,end-start,value.length());
        for(TextWatcher watcher:current)watcher.afterTextChanged(text);
    }
    public void replaceEditable(String value){
        int oldLength=text==null?0:text.length();
        for(TextWatcher watcher:new ArrayList<>(watchers))watcher.beforeTextChanged(text,0,oldLength,value.length());
        text=new ModelEditable(value);layout=new Layout(text);selectionStart=selectionEnd=value.length();
        for(TextWatcher watcher:new ArrayList<>(watchers))watcher.onTextChanged(text,0,oldLength,value.length());
        for(TextWatcher watcher:new ArrayList<>(watchers))watcher.afterTextChanged(text);
    }
}
