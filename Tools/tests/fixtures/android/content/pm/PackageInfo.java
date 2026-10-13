package android.content.pm;
public class PackageInfo { public String packageName;public int versionCode;public Signature[] signatures;public SigningInfo signingInfo;
public ApplicationInfo applicationInfo;
public static class ApplicationInfo { public Metadata metaData; }
public static class Metadata extends java.util.HashMap<String,String> { public String getString(String key){return get(key);} }
public long getLongVersionCode(){return versionCode;}
public static class SigningInfo { public boolean hasMultipleSigners(){return false;} public Signature[] getApkContentsSigners(){return null;} public Signature[] getSigningCertificateHistory(){return null;} }
}
