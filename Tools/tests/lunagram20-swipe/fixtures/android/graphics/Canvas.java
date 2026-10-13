package android.graphics;
import java.util.*;
/** Records production draw order and coordinates; not Android rasterization or font metrics. */
public class Canvas {
 public final List<String> events=new ArrayList<>();public final List<float[]> rectangles=new ArrayList<>();public final Map<String,float[]> labels=new LinkedHashMap<>();
 private final List<float[]> stack=new ArrayList<>();private float x,y;
 public int save(){int result=stack.size()+1;stack.add(new float[]{x,y});return result;}
 public void restore(){float[] p=stack.remove(stack.size()-1);x=p[0];y=p[1];}
 public void restoreToCount(int count){while(stack.size()>=count)restore();}
 public void translate(float dx,float dy){x+=dx;y+=dy;}
 public void clipRect(float l,float t,float r,float b){}
 public void drawRoundRect(RectF r,float rx,float ry,Paint p){events.add("rect");rectangles.add(new float[]{r.left+x,r.top+y,r.right+x,r.bottom+y,p.color,p.alpha});}
 public void label(String value,int width,int height){events.add("label:"+value);labels.put(value,new float[]{x,y,width,height});}
 public boolean balanced(){return stack.isEmpty();}
}
