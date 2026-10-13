package android.text;
/** Deliberately modeled monospace-like wrapping; native text appearance is not claimed. */
public class StaticLayout {
 private final String text;private final int width,height;
 public StaticLayout(String value,TextPaint paint,int width,Layout.Alignment alignment,float spacing,float extra,boolean pad){text=value;this.width=width;height=Math.max(1,(int)Math.ceil(paint.measureText(value)/width))*(int)Math.ceil(paint.size*1.25f);}
 public int getHeight(){return height;}
 public void draw(android.graphics.Canvas canvas){canvas.label(text,width,height);}
}
