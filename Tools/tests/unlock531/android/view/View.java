package android.view;
import android.content.Context;
public class View {
    public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
    private final Context context;
    public int visibility;
    public View(Context context) { this.context = context; }
    public Context getContext() { return context; }
    public void setVisibility(int value) { visibility = value; }
}
