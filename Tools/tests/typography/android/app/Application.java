package android.app;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

public final class Application {
    public final List<ActivityLifecycleCallbacks> callbacks = new ArrayList<>();
    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, Bundle state);
        void onActivityStarted(Activity activity);
        void onActivityResumed(Activity activity);
        void onActivityPaused(Activity activity);
        void onActivityStopped(Activity activity);
        void onActivitySaveInstanceState(Activity activity, Bundle state);
        void onActivityDestroyed(Activity activity);
    }
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) { callbacks.add(callback); }
}
