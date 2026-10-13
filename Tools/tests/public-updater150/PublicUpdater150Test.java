package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import org.telegram.ui.web.HttpGetTask;
import org.telegram.ui.web.HttpGetFileTask;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Full production controller with controlled platform/JSON/HTTP models, not live endpoint or device proof. */
public final class PublicUpdater150Test {
    static final String FEED = "https://lunagram.matveidrugmoi.chatgpt.site/updates/public.json";
    static final String URL = "https://github.com/Lunagram-App/Lunagram/releases/download/v1.5-hotfix-71729/Lunagram-1.5-public-hotfix-71729-arm64.apk";
    static int assertions, sequence;
    static File sandbox;
    static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    static SharedPreferences prefs(String name) { return ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE); }
    static void reset() throws Exception {
        Utilities.globalQueue.drain(); AndroidUtilities.reset(); HttpGetTask.requests.clear(); HttpGetFileTask.requests.clear(); JSONObject.responses.clear();
        prefs("luma_updates").edit().clear().apply(); prefs("luma_updates_public").edit().clear().apply();
        ApplicationLoader.applicationContext.root = new File(sandbox, "case-" + (++sequence));
        check(ApplicationLoader.applicationContext.getFilesDir().mkdirs(), "isolated sandbox created");
        BuildConfig.LUMA_FRIENDS_EDITION = false; BuildConfig.LUMA_PUBLIC_EDITION = true;
        PackageManager.installedVersion = 71699; PackageManager.archiveVersion = 71729;
        PackageManager.archiveEdition = "public"; PackageManager.archivePackage = "org.luma.liquid.web";
        PackageManager.applicationInfoMissing = PackageManager.metadataMissing = PackageManager.wrongSigner = false;
        ApplicationLoader.installPermission = false; AndroidUtilities.openedFiles = 0;
        Field field = LumaUpdaterController.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null, null);
    }
    static LumaUpdaterController controller() { LumaUpdaterController c = LumaUpdaterController.getInstance(); Utilities.globalQueue.drain(); return c; }
    static HttpGetTask latest() { return HttpGetTask.requests.get(HttpGetTask.requests.size() - 1); }
    static Map<String,Object> manifest() {
        Map<String,Object> value = new HashMap<>();
        value.put("version", "1.5"); value.put("version_code", 71729); value.put("file_url", URL);
        value.put("sha256", "a".repeat(64)); value.put("changelog", "Public hotfix");
        value.put("edition", "public"); value.put("package_name", "org.luma.liquid.web"); value.put("architecture", "arm64-v8a");
        return value;
    }
    static void deliver(Map<String,Object> value) { String body = "fixture-" + (++sequence); JSONObject.responses.put(body, value); latest().deliver(body); }
    static Object invoke(LumaUpdaterController c, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = LumaUpdaterController.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(c, args);
    }
    static void rejected(String key, Object value) throws Exception {
        reset(); LumaUpdaterController c = controller(); c.checkForUpdate(true, null);
        Map<String,Object> data = manifest(); if (value == null) data.remove(key); else data.put(key, value); deliver(data);
        check(c.getUpdate() == null && c.getLastError() != null, "rejected " + key + "=" + value);
        check(HttpGetTask.requests.size() == 1 && !c.isChecking(), "public failure never queries personal API/raw fallback");
    }
    static String verify(LumaUpdaterController c, File file, String hash, int code) throws Exception {
        return (String) invoke(c, "verifyDownloadedApk", new Class<?>[] { File.class, String.class, int.class }, file, hash, code);
    }
    static LumaUpdaterController restoredPublicCache() throws Exception {
        reset();
        File apk = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/lunagram-public-updates/luma-update-71729.apk");
        apk.getParentFile().mkdirs(); Files.write(apk.toPath(), new byte[] {1,2,3,4});
        String hash = (String) invoke(null, "calculateSha256", new Class<?>[] {File.class}, apk);
        prefs("luma_updates_public").edit().putString("edition", "public").putString("version", "1.5").putInt("version_code", 71729)
                .putString("sha256", hash).putString("file_url", URL).putString("path", apk.getAbsolutePath()).apply();
        ApplicationLoader.installPermission = true;
        return controller();
    }
    public static void main(String[] args) throws Exception {
        sandbox = new File(args[0]);
        reset();
        prefs("luma_updates").edit().putString("manifest_url", BuildVars.LUMA_UPDATE_MANIFEST_URL).putInt("version_code", 999999).apply();
        prefs("luma_updates_public").edit().putString("manifest_url", "https://bad.example/feed").apply();
        File personal = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/luma-update-71600.apk");
        personal.getParentFile().mkdirs(); Files.write(personal.toPath(), new byte[] {1});
        LumaUpdaterController c = controller();
        check(LumaBuildPolicy.allowsBuiltInUpdates() && FEED.equals(c.getManifestUrl()), "public uses pinned feed despite both persisted source overrides");
        check(c.getUpdate() == null && c.getDownloadedFile() == null, "personal release state is not inherited");
        check(personal.isFile() && prefs("luma_updates").getInt("version_code", 0) == 999999, "public cleanup cannot touch personal cache/prefs");
        for (String source : new String[] { "", null, BuildVars.LUMA_UPDATE_MANIFEST_URL, "https://bad.example/feed", FEED + "?override=1" })
            check(!c.setManifestUrl(source), "public rejects custom source");
        check(c.setManifestUrl(FEED) && HttpGetTask.requests.isEmpty(), "same pinned source is a no-op");
        c.checkForUpdate(true, null);
        check(latest().url.startsWith(FEED + "?") && !latest().url.contains("api.github.com"), "public checks exact site feed directly");
        check(latest().strict && latest().maxBytes == 65536, "existing bounded strict transport retained");
        deliver(manifest()); check(c.getUpdate() != null && c.getUpdate().versionCode == 71729, "valid public hotfix accepted");
        check(c.getUpdate().higherThan(new BetaUpdate("99.0", 71699, null)), "Android code remains monotonic regardless marketing name");
        c.downloadUpdate(); check(HttpGetFileTask.requests.size() == 1, "public download starts");
        HttpGetFileTask download = HttpGetFileTask.requests.get(0);
        check(URL.equals(download.url), "download uses exact versioned public asset");
        check(download.destination.getParentFile().getCanonicalPath().endsWith("cache" + File.separator + "lunagram-public-updates"), "public staging uses separate directory");

        for (String key : new String[] { "edition", "package_name", "architecture" }) rejected(key, null);
        for (String edition : new String[] { "personal", "friends", "Public", "public " }) rejected("edition", edition);
        rejected("package_name", "org.telegram.messenger"); rejected("architecture", "x86_64"); rejected("architecture", "universal");
        rejected("version", "1.5/.."); rejected("version", "1.5-test"); rejected("version_code", 0); rejected("sha256", "not-a-hash");
        for (String url : new String[] {
            URL.replace("Lunagram-App", "attacker"), URL.replace("-public-", "-personal-"),
            URL.replace("v1.5-hotfix-71729", "v1.5-hotfix-71719"), URL.replace("https://", "http://"),
            URL.replace("github.com/", "github.com.evil.example/"), URL.replace("github.com/", "github.com:443/"),
            URL.replace("github.com/", "user@github.com/"), URL + "?x=1", URL + "#fragment",
            URL.replace("/releases/download/", "/releases/latest/download/"), "../Lunagram-1.5-public-hotfix-71729-arm64.apk",
            URL.replace("-arm64.apk", "-x86.apk"), URL.replace("Lunagram-1.5", "Lunagram-1.6") }) rejected("file_url", url);
        reset(); c = controller(); c.checkForUpdate(true, null); Map<String,Object> release = manifest();
        release.put("file_url", "https://github.com/Lunagram-App/Lunagram/releases/download/v1.5/Lunagram-1.5-public-arm64.apk");
        deliver(release); check(c.getUpdate() != null, "ordinary immutable public versioned release is also valid");
        reset(); c = controller(); c.checkForUpdate(true, null); latest().deliver(null);
        check(!c.isChecking() && HttpGetTask.requests.size() == 1 && c.getLastError() != null, "public network failure does not fall back to personal");

        reset(); File wrongPath = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/luma-update-71729.apk");
        wrongPath.getParentFile().mkdirs(); Files.write(wrongPath.toPath(), new byte[] {1});
        prefs("luma_updates_public").edit().putString("edition", "public").putString("version", "1.5").putInt("version_code", 71729)
                .putString("sha256", "a".repeat(64)).putString("file_url", URL).putString("path", wrongPath.getAbsolutePath()).apply();
        c = controller(); check(c.getDownloadedFile() == null && c.getUpdate() == null && wrongPath.isFile(), "out-of-edition cached path rejected without deletion");

        reset(); c = controller(); File apk = new File(ApplicationLoader.applicationContext.getFilesDir(), "fixture.apk");
        Files.write(apk.toPath(), new byte[] {1,2,3,4});
        String hash = (String) invoke(c, "calculateSha256", new Class<?>[] {File.class}, apk);
        check(verify(c, apk, hash, 71729) == null, "valid public signed archive accepted");
        check((PackageManager.archiveFlags & PackageManager.GET_META_DATA) != 0, "archive asks Android for signed metadata");
        for (String edition : new String[] { null, "personal", "friends", "Public", "public " }) {
            PackageManager.archiveEdition = edition; check(verify(c, apk, hash, 71729) != null, "archive edition fails closed: " + edition);
        }
        PackageManager.archiveEdition = "public"; PackageManager.applicationInfoMissing = true;
        check(verify(c, apk, hash, 71729) != null, "missing application metadata fails closed");
        PackageManager.applicationInfoMissing = false; PackageManager.metadataMissing = true;
        check(verify(c, apk, hash, 71729) != null, "missing metadata bundle fails closed"); PackageManager.metadataMissing = false;
        check(verify(c, apk, "0".repeat(64), 71729) != null, "hash verification retained");
        PackageManager.archivePackage = "other.app"; check(verify(c, apk, hash, 71729) != null, "package verification retained");
        PackageManager.archivePackage = "org.luma.liquid.web"; PackageManager.archiveVersion = 71719;
        check(verify(c, apk, hash, 71729) != null, "manifest/archive version mismatch rejected");
        PackageManager.archiveVersion = 71729; PackageManager.installedVersion = 71729;
        check(verify(c, apk, hash, 71729) != null, "same-code reinstall not an update");
        PackageManager.installedVersion = 71699; PackageManager.wrongSigner = true;
        check(verify(c, apk, hash, 71729) != null, "different certificate rejected");

        c = restoredPublicCache();
        check(c.getDownloadedFile() != null, "owned restored APK remains available for verified install");
        check(c.install(new android.app.Activity()) && AndroidUtilities.openedFiles == 0, "public install verification is asynchronous");
        Utilities.globalQueue.drain(); check(AndroidUtilities.openedFiles == 1, "verified public cache opens Android installer once");
        c = restoredPublicCache(); File corrupt = c.getDownloadedFile(); Files.write(corrupt.toPath(), new byte[] {9});
        check(c.install(new android.app.Activity()), "restored cache attempt starts verification");
        Utilities.globalQueue.drain();
        check(AndroidUtilities.openedFiles == 0 && c.getDownloadedFile() == null && c.getLastError() != null && !corrupt.exists(), "corrupt cached bytes never open installer, are safely invalidated");
        c = restoredPublicCache(); PackageManager.archiveEdition = "personal";
        c.install(new android.app.Activity()); Utilities.globalQueue.drain();
        check(AndroidUtilities.openedFiles == 0 && c.getDownloadedFile() == null, "restored wrong-edition cache cannot open installer");
        c = restoredPublicCache(); android.app.Activity closed = new android.app.Activity();
        c.install(closed); closed.destroyed = true; Utilities.globalQueue.drain();
        check(AndroidUtilities.openedFiles == 0, "destroyed activity cannot launch late installer");
        c = restoredPublicCache(); c.install(new android.app.Activity());
        c.checkForUpdate(true, null); Map<String,Object> replaced = manifest(); replaced.put("version_code", 71739);
        replaced.put("file_url", URL.replace("71729", "71739")); deliver(replaced);
        Utilities.globalQueue.drain(); check(AndroidUtilities.openedFiles == 0, "obsolete install verification cannot launch replaced APK");
        c = restoredPublicCache(); c.install(new android.app.Activity()); c.install(new android.app.Activity());
        Utilities.globalQueue.drain(); check(AndroidUtilities.openedFiles == 1, "repeated install taps supersede prior verification instead of duplicate installers");

        reset(); BuildConfig.LUMA_PUBLIC_EDITION = false; c = controller();
        check(BuildVars.LUMA_UPDATE_MANIFEST_URL.equals(c.getManifestUrl()), "personal default unchanged");
        check(c.setManifestUrl("https://personal.example/feed"), "personal custom feed remains supported");
        check(latest().url.startsWith("https://personal.example/feed?"), "personal override queried directly");
        File legacy = new File(ApplicationLoader.applicationContext.getFilesDir(), "legacy.apk"); Files.write(legacy.toPath(), new byte[] {1});
        String legacyHash = (String) invoke(c, "calculateSha256", new Class<?>[] {File.class}, legacy);
        PackageManager.applicationInfoMissing = true;
        check(verify(c, legacy, legacyHash, 71729) == null, "personal legacy APK without metadata remains accepted");
        reset(); BuildConfig.LUMA_FRIENDS_EDITION = true; BuildConfig.LUMA_PUBLIC_EDITION = false; c = controller();
        final int[] done = {0}; c.checkForUpdate(true, () -> done[0]++); c.downloadUpdate();
        check(!LumaBuildPolicy.allowsBuiltInUpdates() && done[0] == 1 && HttpGetTask.requests.isEmpty() && HttpGetFileTask.requests.isEmpty(), "Friends performs no update network calls");
        check(c.getManifestUrl().isEmpty() && !c.setManifestUrl(FEED) && c.getDownloadedFile() == null && c.getUpdate() == null, "Friends remains disabled");
        System.out.println("PASS public updater: " + assertions + " assertions; production controller, controlled Android/JSON/HTTP contracts.");
    }
}
