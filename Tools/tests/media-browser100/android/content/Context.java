package android.content;
import android.content.pm.PackageManager;
public class Context {
    public final PackageManager packages = new PackageManager();
    public PackageManager getPackageManager() { return packages; }
}
