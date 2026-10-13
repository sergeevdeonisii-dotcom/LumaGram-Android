package android.content.res;
public class Resources {
 public static class Metrics {public float scaledDensity=1;}
 private final Metrics metrics=new Metrics();
 public Metrics getDisplayMetrics(){return metrics;}
 public android.graphics.drawable.Drawable getDrawable(int id){return new android.graphics.drawable.Drawable();}
}
