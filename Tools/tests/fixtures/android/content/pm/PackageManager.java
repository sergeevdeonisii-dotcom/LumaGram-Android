package android.content.pm;
public class PackageManager {
 public static final int GET_SIGNING_CERTIFICATES=1,GET_SIGNATURES=2;
 public static int installedVersion=71439;
 public PackageInfo getPackageInfo(String name,int flags){PackageInfo p=new PackageInfo();p.packageName=name;p.versionCode=installedVersion;return p;}
 public PackageInfo getPackageArchiveInfo(String path,int flags){return null;}
}
