package android.view;

import java.util.ArrayList;
import java.util.List;

public class ViewGroup extends View {
    private final List<View> children = new ArrayList<>();
    public int childReads;
    public void addView(View view) { children.add(view); }
    public int getChildCount() { childReads++; return children.size(); }
    public View getChildAt(int index) { childReads++; return children.get(index); }
}
