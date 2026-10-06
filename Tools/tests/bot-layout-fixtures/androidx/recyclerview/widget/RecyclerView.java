package androidx.recyclerview.widget;

import android.view.View;
import java.util.ArrayList;

public class RecyclerView extends View {
    public int height, paddingTop, paddingBottom;
    public final ArrayList<View> children = new ArrayList<>();
    public int getHeight() { return height; }
    public int getPaddingTop() { return paddingTop; }
    public int getPaddingBottom() { return paddingBottom; }
    public int getChildCount() { return children.size(); }
    public View getChildAt(int index) { return children.get(index); }
}
