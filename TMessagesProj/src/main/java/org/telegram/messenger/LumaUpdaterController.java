package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Secure update channel for Luma builds. The manifest and APK are downloaded over HTTPS,
 * then the APK is accepted only when both its SHA-256 and signing certificate match.
 */
public final class LumaUpdaterController {

    public interface DownloadListener {
        void onProgress(float progress);
        void onFinished(File file, String error);
    }

    // LaunchActivity calls us whenever Luma returns to the foreground. A short throttle keeps
    // those checks invisible and inexpensive while still surfacing releases quickly.
    private static final long CHECK_INTERVAL = 15L * 60L * 1000L;
    private static final long MAX_APK_SIZE = 1024L * 1024L * 1024L;
    private static volatile LumaUpdaterController instance;

    public static LumaUpdaterController getInstance() {
        LumaUpdaterController result = instance;
        if (result == null) {
            synchronized (LumaUpdaterController.class) {
                result = instance;
                if (result == null) {
                    instance = result = new LumaUpdaterController();
                }
            }
        }
        return result;
    }

    private final ArrayList<DownloadListener> downloadListeners = new ArrayList<>();
    private String version;
    private int versionCode;
    private String changelog;
    private String fileUrl;
    private String sha256;
    private String path;
    private long lastCheck;
    private boolean checking;
    private boolean downloading;
    private float downloadingProgress;
    private String lastError;
    private HttpGetFileTask downloadingTask;
    private int checkGeneration;
    private int downloadGeneration;

    private LumaUpdaterController() {
        load();
    }

    private SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("luma_updates", Context.MODE_PRIVATE);
    }

    private void load() {
        SharedPreferences prefs = preferences();
        version = prefs.getString("version", null);
        versionCode = prefs.getInt("version_code", 0);
        changelog = prefs.getString("changelog", null);
        fileUrl = prefs.getString("file_url", null);
        sha256 = prefs.getString("sha256", null);
        path = prefs.getString("path", null);
        lastCheck = prefs.getLong("last_check", 0L);
        if (versionCode <= getCurrentVersionCode() || !TextUtils.isEmpty(path) && !new File(path).exists()) {
            clearPendingUpdate(false);
        }
    }

    private void save() {
        SharedPreferences.Editor editor = preferences().edit();
        putOrRemove(editor, "version", version);
        putOrRemove(editor, "changelog", changelog);
        putOrRemove(editor, "file_url", fileUrl);
        putOrRemove(editor, "sha256", sha256);
        putOrRemove(editor, "path", path);
        if (versionCode == 0) {
            editor.remove("version_code");
        } else {
            editor.putInt("version_code", versionCode);
        }
        if (lastCheck == 0L) {
            editor.remove("last_check");
        } else {
            editor.putLong("last_check", lastCheck);
        }
        editor.apply();
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        if (TextUtils.isEmpty(value)) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    public String getManifestUrl() {
        return preferences().getString("manifest_url", BuildVars.LUMA_UPDATE_MANIFEST_URL);
    }

    public boolean setManifestUrl(String value) {
        value = value == null ? "" : value.trim();
        if (!TextUtils.isEmpty(value) && !isHttps(value)) {
            return false;
        }
        if (!TextUtils.equals(value, getManifestUrl())) {
            preferences().edit().putString("manifest_url", value).apply();
            clearPendingUpdate(true);
            lastCheck = 0L;
            save();
        }
        return true;
    }

    public boolean hasManifestUrl() {
        return isHttps(getManifestUrl());
    }

    public boolean isAutoCheckEnabled() {
        return preferences().getBoolean("auto_check", true);
    }

    public void setAutoCheckEnabled(boolean enabled) {
        preferences().edit().putBoolean("auto_check", enabled).apply();
    }

    public boolean isChecking() {
        return checking;
    }

    public String getLastError() {
        return lastError;
    }

    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (checking) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        if (!force && !isAutoCheckEnabled()) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        final String manifestUrl = getManifestUrl();
        if (!isHttps(manifestUrl)) {
            lastError = LocaleController.getString(R.string.LumaUpdateSourceMissing);
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        if (!force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }

        checking = true;
        lastError = null;
        final int generation = ++checkGeneration;
        String requestUrl = appendCacheBuster(manifestUrl);
        new HttpGetTask(response -> AndroidUtilities.runOnUIThread(() -> {
            if (generation != checkGeneration) {
                return;
            }
            checking = false;
            if (TextUtils.isEmpty(response)) {
                lastError = LocaleController.getString(R.string.LumaUpdateCheckFailed);
            } else {
                try {
                    JSONObject json = new JSONObject(response);
                    String newVersion = json.getString("version").trim();
                    int newVersionCode = json.getInt("version_code");
                    String newFileUrl = resolveUrl(manifestUrl, json.getString("file_url"));
                    String newSha256 = json.getString("sha256").trim().toLowerCase(Locale.US);
                    String newChangelog = json.optString("changelog", null);
                    if (TextUtils.isEmpty(newVersion) || newVersionCode <= 0 || !isHttps(newFileUrl) || !newSha256.matches("[0-9a-f]{64}")) {
                        throw new IllegalArgumentException("Invalid update manifest");
                    }
                    if (newVersionCode > getCurrentVersionCode()) {
                        boolean changed = newVersionCode != versionCode || !TextUtils.equals(newSha256, sha256);
                        if (changed) {
                            deleteDownloadedFile();
                        }
                        version = newVersion;
                        versionCode = newVersionCode;
                        fileUrl = newFileUrl;
                        sha256 = newSha256;
                        changelog = newChangelog;
                    } else {
                        clearPendingUpdate(true);
                    }
                    lastCheck = System.currentTimeMillis();
                    save();
                } catch (Exception e) {
                    FileLog.e("Invalid Luma update manifest at " + manifestUrl, e);
                    lastError = LocaleController.getString(R.string.LumaUpdateManifestInvalid);
                }
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            if (whenDone != null) {
                whenDone.run();
            }
        })).setHeader("Accept", "application/json")
                .setHeader("Cache-Control", "no-cache")
                .setHeader("User-Agent", "Luma-Android/" + BuildVars.BUILD_VERSION_STRING)
                .execute(requestUrl);
    }

    public BetaUpdate getUpdate() {
        if (versionCode <= getCurrentVersionCode() || TextUtils.isEmpty(version)) {
            return null;
        }
        return new BetaUpdate(version, versionCode, changelog);
    }

    public void addDownloadListener(DownloadListener listener) {
        if (listener != null && !downloadListeners.contains(listener)) {
            downloadListeners.add(listener);
        }
    }

    public void removeDownloadListener(DownloadListener listener) {
        downloadListeners.remove(listener);
    }

    public void downloadUpdate() {
        downloadUpdate(null);
    }

    public void downloadUpdate(DownloadListener listener) {
        addDownloadListener(listener);
        File existing = getDownloadedFile();
        if (existing != null) {
            notifyDownloadFinished(existing, null);
            return;
        }
        if (downloading) {
            if (listener != null) {
                listener.onProgress(downloadingProgress);
            }
            return;
        }
        if (getUpdate() == null || !isHttps(fileUrl) || TextUtils.isEmpty(sha256)) {
            notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateManifestInvalid));
            return;
        }

        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        if (!directory.exists() && !directory.mkdirs()) {
            notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateDownloadFailed));
            return;
        }
        File destination = new File(directory, "luma-update-" + versionCode + ".apk");
        if (destination.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
        }

        downloading = true;
        downloadingProgress = 0f;
        lastError = null;
        final int generation = ++downloadGeneration;
        notifyDownloadProgress();
        downloadingTask = new HttpGetFileTask(downloadedFile -> {
            if (generation != downloadGeneration) {
                return;
            }
            if (downloadedFile == null) {
                downloading = false;
                downloadingTask = null;
                lastError = LocaleController.getString(R.string.LumaUpdateDownloadFailed);
                notifyDownloadFinished(null, lastError);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                String verificationError = verifyDownloadedApk(downloadedFile);
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation != downloadGeneration) {
                        return;
                    }
                    downloading = false;
                    downloadingTask = null;
                    if (verificationError == null) {
                        path = downloadedFile.getAbsolutePath();
                        downloadingProgress = 1f;
                        save();
                        notifyDownloadFinished(downloadedFile, null);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        downloadedFile.delete();
                        lastError = verificationError;
                        notifyDownloadFinished(null, verificationError);
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                });
            });
        }, progress -> {
            if (generation != downloadGeneration) {
                return;
            }
            downloadingProgress = progress;
            notifyDownloadProgress();
        }).setDestFile(destination).setMaxSize(MAX_APK_SIZE).setOverrideExtension("apk");
        downloadingTask.execute(fileUrl);
    }

    public void cancelDownloadingUpdate() {
        if (!downloading) {
            return;
        }
        ++downloadGeneration;
        if (downloadingTask != null) {
            downloadingTask.cancel(false);
        }
        downloadingTask = null;
        downloading = false;
        downloadingProgress = 0f;
        notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateCancelled));
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    public File getDownloadedFile() {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }

    public boolean install(Activity activity) {
        File file = getDownloadedFile();
        if (activity == null || file == null) {
            return false;
        }
        if (!ApplicationLoader.applicationLoaderInstance.checkApkInstallPermissions(activity)) {
            return false;
        }
        return AndroidUtilities.openForView(file, "Luma.apk", "application/vnd.android.package-archive", activity, null, false);
    }

    private void notifyDownloadProgress() {
        ArrayList<DownloadListener> snapshot = new ArrayList<>(downloadListeners);
        for (DownloadListener listener : snapshot) {
            listener.onProgress(downloadingProgress);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
    }

    private void notifyDownloadFinished(File file, String error) {
        ArrayList<DownloadListener> snapshot = new ArrayList<>(downloadListeners);
        downloadListeners.clear();
        for (DownloadListener listener : snapshot) {
            listener.onFinished(file, error);
        }
    }

    private String verifyDownloadedApk(File file) {
        try {
            if (!TextUtils.equals(sha256, calculateSha256(file))) {
                return LocaleController.getString(R.string.LumaUpdateHashMismatch);
            }
            PackageManager packageManager = ApplicationLoader.applicationContext.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = packageManager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            PackageInfo installed = packageManager.getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), flags);
            if (archive == null || !TextUtils.equals(archive.packageName, ApplicationLoader.applicationContext.getPackageName())) {
                return LocaleController.getString(R.string.LumaUpdatePackageMismatch);
            }
            long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? archive.getLongVersionCode() : archive.versionCode;
            if (archiveVersion != versionCode || archiveVersion <= getCurrentVersionCode()) {
                return LocaleController.getString(R.string.LumaUpdateVersionMismatch);
            }
            Set<String> archiveSignatures = signatureDigests(archive);
            Set<String> installedSignatures = signatureDigests(installed);
            archiveSignatures.retainAll(installedSignatures);
            if (archiveSignatures.isEmpty()) {
                return LocaleController.getString(R.string.LumaUpdateSignatureMismatch);
            }
            return null;
        } catch (Exception e) {
            FileLog.e("Unable to verify downloaded Luma update", e);
            return LocaleController.getString(R.string.LumaUpdateVerificationFailed);
        }
    }

    private static Set<String> signatureDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                result.add(toHex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
            }
        }
        return result;
    }

    private static String calculateSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String resolveUrl(String base, String value) throws Exception {
        return new URL(new URL(base), value).toString();
    }

    private static boolean isHttps(String value) {
        return !TextUtils.isEmpty(value) && value.regionMatches(true, 0, "https://", 0, 8);
    }

    private static String appendCacheBuster(String value) {
        return value + (value.contains("?") ? "&" : "?") + "luma_check=" + System.currentTimeMillis();
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo info = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            return (int) Math.min(Integer.MAX_VALUE, code);
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    private void clearPendingUpdate(boolean deleteFile) {
        if (deleteFile) {
            deleteDownloadedFile();
        }
        version = null;
        versionCode = 0;
        changelog = null;
        fileUrl = null;
        sha256 = null;
        path = null;
        save();
    }

    private void deleteDownloadedFile() {
        if (!TextUtils.isEmpty(path)) {
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(path).delete();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        path = null;
    }
}
