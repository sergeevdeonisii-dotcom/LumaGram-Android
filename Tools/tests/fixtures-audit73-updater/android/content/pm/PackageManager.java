package android.content.pm;

public class PackageManager {
    public static final int GET_SIGNING_CERTIFICATES = 1, GET_SIGNATURES = 2;
    public static int installedVersion = 71439;
    public static int archiveVersion = 71500;
    public PackageInfo getPackageInfo(String name, int flags) { return info(name, installedVersion); }
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        return info("org.luma.liquid.web", archiveVersion);
    }
    private static PackageInfo info(String name, int version) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionCode = version;
        info.signatures = new Signature[] { new Signature() };
        return info;
    }
}
