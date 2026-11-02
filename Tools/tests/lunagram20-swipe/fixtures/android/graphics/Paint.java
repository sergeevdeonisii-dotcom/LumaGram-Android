package android.graphics;
public class Paint {
 public static final int ANTI_ALIAS_FLAG=1; public int color,alpha=255;
 public Paint(int flags){} public void setColor(int value){color=value;}public void setAlpha(int value){alpha=value;}
}
