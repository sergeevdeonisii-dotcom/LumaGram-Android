package android.content.pm;
import android.content.ComponentName;
import java.util.*;
public class PackageManager {
    public static final int COMPONENT_ENABLED_STATE_DEFAULT = 0, COMPONENT_ENABLED_STATE_ENABLED = 1,
            COMPONENT_ENABLED_STATE_DISABLED = 2, DONT_KILL_APP = 1;
    public final Map<ComponentName, Integer> states = new HashMap<>();
    public final List<Integer> writes = new ArrayList<>();
    public boolean failNext;
    public int getComponentEnabledSetting(ComponentName name) { return states.getOrDefault(name, 0); }
    public void setComponentEnabledSetting(ComponentName name, int state, int flags) {
        if (failNext) { failNext = false; throw new IllegalStateException("OEM failure"); }
        if (flags != DONT_KILL_APP) throw new AssertionError("Must not kill app");
        writes.add(state);
        states.put(name, state);
    }
}
