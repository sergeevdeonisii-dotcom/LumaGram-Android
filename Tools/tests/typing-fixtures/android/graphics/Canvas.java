package android.graphics;
import java.util.ArrayList;
public class Canvas {
    public static final class Draw {
        public final String text; public final float x,y; public final int alpha;
        Draw(String value,float x,float y,int alpha) {text=value;this.x=x;this.y=y;this.alpha=alpha;}
    }
    public final ArrayList<Draw> draws=new ArrayList<>();
    public float left,top,right,bottom;
    public int saves;
    public int save() { return ++saves; }
    public void restore() { if (--saves<0) throw new AssertionError("unbalanced canvas"); }
    public boolean clipRect(float l,float t,float r,float b) {left=l;top=t;right=r;bottom=b;return true;}
    public void drawText(String value,int start,int end,float x,float y,Paint paint) {
        draws.add(new Draw(value.substring(start,end),x,y,paint.getAlpha()));
    }
}
