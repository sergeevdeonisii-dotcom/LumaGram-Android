package android.widget;

import android.graphics.Typeface;
import android.view.View;

public class TextView extends View {
    private Typeface typeface;
    private Object transformation;
    public int typefaceWrites;
    public final String text = "Original message content";
    public Typeface getTypeface() { return typeface; }
    public void setTypeface(Typeface next) { typeface = next; typefaceWrites++; }
    public Object getTransformationMethod() { return transformation; }
    public void setTransformationMethod(Object value) { transformation = value; }
}
