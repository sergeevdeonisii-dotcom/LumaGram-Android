package android.content.pm;
import java.util.*;
public class PackageManager {
    public static final int PERMISSION_GRANTED = 0, SIGNATURE_MATCH = 0;
    public final Map<Integer, String[]> owners = new HashMap<>();
    public final Set<String> granted = new HashSet<>(), platformSigned = new HashSet<>();
    public boolean fail;
    public String[] getPackagesForUid(int uid) {
        if (fail) throw new SecurityException("fixture");
        return owners.get(uid);
    }
    public int checkPermission(String permission, String name) { return granted.contains(name + ":" + permission) ? 0 : -1; }
    public int checkSignatures(String platform, String name) { return "android".equals(platform) && platformSigned.contains(name) ? 0 : -1; }
}
