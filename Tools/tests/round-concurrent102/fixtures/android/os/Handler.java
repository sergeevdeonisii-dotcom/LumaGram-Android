package android.os;
import java.util.ArrayList;
public class Handler {
    public static Handler lastAutomatic;
    public boolean automatic, rejectPosts;
    public Handler() {}
    public Handler(Object looper) { automatic = true; lastAutomatic = this; }
    public final ArrayList<Runnable> immediate = new ArrayList<>(), delayed = new ArrayList<>();
    public boolean post(Runnable r) { if (rejectPosts) return false; if (automatic) r.run(); else immediate.add(r); return true; }
    public boolean postDelayed(Runnable r, long delay) { delayed.add(r); return true; }
    public void removeCallbacks(Runnable r) { immediate.removeIf(x -> x == r); delayed.removeIf(x -> x == r); }
    public void drain() { while (!immediate.isEmpty()) immediate.remove(0).run(); }
    public void timeout() { ArrayList<Runnable> copy = new ArrayList<>(delayed); delayed.clear(); for (Runnable r : copy) r.run(); drain(); }
}
