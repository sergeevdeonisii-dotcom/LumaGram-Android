package android.text;
public class TextPaint extends android.graphics.Paint {public float size;public TextPaint(int flags){super(flags);}public void setTextSize(float value){size=value;}public float measureText(String value){return value.codePointCount(0,value.length())*size*.55f;}}
