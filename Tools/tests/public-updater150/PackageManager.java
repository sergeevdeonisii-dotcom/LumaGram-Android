package android.content.pm;

/** Archive/installed APK metadata model; no real certificate parsing or phone operations. */
public final class PackageManager {
    public static final int GET_SIGNING_CERTIFICATES = 1, GET_SIGNATURES = 2, GET_META_DATA = 128;
    public static int installedVersion = 71699, archiveVersion = 71729, archiveFlags;
    public static String archivePackage = "org.luma.liquid.web", archiveEdition = "public";
    public static boolean applicationInfoMissing, metadataMissing, wrongSigner;
    public PackageInfo getPackageInfo(String name, int flags) { return info(name, installedVersion, false); }
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        archiveFlags = flags;
        return info(archivePackage, archiveVersion, true);
    }
    private PackageInfo info(String name, int version, boolean archive) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionCode = version;
        info.signatures = new Signature[] { new Signature() {
            @Override public byte[] toByteArray() { return new byte[] { (byte) (archive && wrongSigner ? 2 : 1) }; }
        }};
        if (!archive || !applicationInfoMissing) {
            info.applicationInfo = new PackageInfo.ApplicationInfo();
            if (!archive || !metadataMissing) {
                info.applicationInfo.metaData = new PackageInfo.Metadata();
                if (archiveEdition != null) info.applicationInfo.metaData.put("org.lunagram.edition", archiveEdition);
            }
        }
        return info;
    }
}
