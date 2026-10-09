package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
    private static final long CHECK_TIMEOUT = 30_000L;
    private static final String OFFICIAL_API_MANIFEST = "https://api.github.com/repos/sergeevdeonisii-dotcom/LumaGram-Android/contents/updates/latest.json?ref=main";
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
    private float lastNotifiedProgress;
    private long lastProgressNotificationTime;
    private String lastError;
    private HttpGetFileTask downloadingTask;
    private int checkGeneration;
    private int checkRequestId;
    private HttpGetTask checkingTask;
    private Runnable checkTimeout;
    private int downloadGeneration;
    private final ArrayList<Runnable> checkCompletions = new ArrayList<>();

    private LumaUpdaterController() {
        // Restricted builds never read the full edition's pending APK or clean
        // its cache. A future manual full-edition install can still use it.
        if (LumaBuildPolicy.allowsBuiltInUpdates()) load();
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
        final int installedVersion = getCurrentVersionCode();
        if (versionCode <= installedVersion || !TextUtils.isEmpty(path) && !new File(path).exists()) {
            clearPendingUpdate(true);
        }
        // Cleanup must finish before another attempt can create a staging file.
        LumaUpdateFiles.cleanupAbandonedDownloads(updateDirectory());
        Utilities.globalQueue.postRunnable(() -> LumaUpdateFiles.cleanupInstalled(updateDirectory(), installedVersion));
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
        if (!LumaBuildPolicy.allowsBuiltInUpdates()) return "";
        String saved = preferences().getString("manifest_url", null);
        // The source editor is no longer exposed. Recover old cleared/corrupt
        // values without losing a valid custom HTTPS source.
        return isHttps(saved) ? saved.trim() : BuildVars.LUMA_UPDATE_MANIFEST_URL;
    }

    public boolean setManifestUrl(String value) {
        if (!LumaBuildPolicy.allowsBuiltInUpdates()) return false;
        value = value == null ? "" : value.trim();
        if (!TextUtils.isEmpty(value) && !isHttps(value)) {
            return false;
        }
        if (TextUtils.isEmpty(value)) {
            value = BuildVars.LUMA_UPDATE_MANIFEST_URL;
        }
        if (!TextUtils.equals(value, getManifestUrl())) {
            ++checkGeneration;
            checking = false;
            cancelCheckingRequest();
            ArrayList<Runnable> completions = takeCheckCompletions();
            preferences().edit().putString("manifest_url", value).apply();
            clearPendingUpdate(true);
            lastCheck = 0L;
            lastError = null;
            save();
            // Observers/listeners may reenter. They must already see the new source/state.
            cancelDownloadingUpdate();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            // Release any spinner owned by the invalidated request. Its late result is ignored.
            runCheckCompletions(completions);
            checkForUpdate(true, null);
        }
        return true;
    }

    private ArrayList<Runnable> takeCheckCompletions() {
        ArrayList<Runnable> result = new ArrayList<>(checkCompletions);
        checkCompletions.clear();
        return result;
    }

    private static void runCheckCompletions(ArrayList<Runnable> completions) {
        for (Runnable completion : completions) {
            try {
                completion.run();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public boolean hasManifestUrl() {
        return LumaBuildPolicy.allowsBuiltInUpdates() && isHttps(getManifestUrl());
    }

    public boolean isAutoCheckEnabled() {
        return LumaBuildPolicy.allowsBuiltInUpdates() && preferences().getBoolean("auto_check", true);
    }

    public void setAutoCheckEnabled(boolean enabled) {
        if (!LumaBuildPolicy.allowsBuiltInUpdates()) return;
        preferences().edit().putBoolean("auto_check", enabled).apply();
    }

    public boolean isChecking() {
        return LumaBuildPolicy.allowsBuiltInUpdates() && checking;
    }

    public String getLastError() {
        return lastError;
    }

    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (!LumaBuildPolicy.allowsBuiltInUpdates()) {
            if (whenDone != null) {
                try {
                    whenDone.run();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return;
        }
        if (!force && LumaEmergencyMode.isEnabled(UserConfig.selectedAccount)) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        if (checking) {
            if (whenDone != null) {
                checkCompletions.add(whenDone);
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
        // A restored backup or a clock correction can leave lastCheck in the future.
        // Only elapsed time in the normal interval should suppress automatic checks.
        final long now = System.currentTimeMillis();
        if (!force && lastCheck > 0L && lastCheck <= now && now - lastCheck < CHECK_INTERVAL) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }

        checking = true;
        if (whenDone != null) checkCompletions.add(whenDone);
        lastError = null;
        final int generation = ++checkGeneration;
        checkTimeout = () -> {
            if (generation != checkGeneration || !checking) return;
            ++checkGeneration;
            checking = false;
            cancelCheckingRequest();
            ArrayList<Runnable> completions = takeCheckCompletions();
            lastError = LocaleController.getString(R.string.LumaUpdateCheckFailed);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            runCheckCompletions(completions);
        };
        AndroidUtilities.runOnUIThread(checkTimeout, CHECK_TIMEOUT);
        requestManifest(generation, manifestUrl, false);
    }

    private void cancelCheckingRequest() {
        ++checkRequestId;
        HttpGetTask task = checkingTask;
        checkingTask = null;
        if (checkTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(checkTimeout);
            checkTimeout = null;
        }
        if (task != null) task.cancelRequest();
    }

    private void requestManifest(int generation, String manifestUrl, boolean fallback) {
        if (generation != checkGeneration || !checking) return;
        final boolean official = TextUtils.equals(manifestUrl, BuildVars.LUMA_UPDATE_MANIFEST_URL);
        final boolean useApi = official && !fallback;
        final int requestId = ++checkRequestId;
        HttpGetTask task = new HttpGetTask(response -> AndroidUtilities.runOnUIThread(() ->
                onManifestResponse(generation, requestId, manifestUrl, fallback, response)))
                .setTimeouts(5000, 10000).setMaxResponseBytes(65536).setStrictResponse(true)
                .setHeader("Accept", useApi ? "application/vnd.github.raw+json" : "application/json")
                .setHeader("Cache-Control", "no-cache")
                .setHeader("User-Agent", "Lunagram-Android/" + BuildVars.BUILD_VERSION_STRING);
        checkingTask = task;
        try {
            task.executeParallel(appendCacheBuster(useApi ? OFFICIAL_API_MANIFEST : manifestUrl));
        } catch (Exception e) {
            onManifestResponse(generation, requestId, manifestUrl, fallback, null);
        }
    }

    private void onManifestResponse(int generation, int requestId, String manifestUrl, boolean fallback, String response) {
        if (generation != checkGeneration || requestId != checkRequestId || !checking) return;
        String newVersion = null, newFileUrl = null, newSha256 = null, newChangelog = null;
        int newVersionCode = 0;
        boolean valid = false;
        if (!TextUtils.isEmpty(response)) {
            try {
                JSONObject json = new JSONObject(response);
                newVersion = json.getString("version").trim();
                newVersionCode = json.getInt("version_code");
                // A fallback is only a transport change. Relative APK links
                // retain the original source base, never the GitHub API path.
                newFileUrl = resolveUrl(manifestUrl, json.getString("file_url"));
                newSha256 = json.getString("sha256").trim().toLowerCase(Locale.US);
                newChangelog = json.optString("changelog", null);
                valid = !TextUtils.isEmpty(newVersion) && newVersionCode > 0 && isHttps(newFileUrl)
                        && newSha256.matches("[0-9a-f]{64}");
            } catch (Exception e) {
                FileLog.e("Invalid Lunagram update manifest", e);
            }
        }
        if (!valid && !fallback && TextUtils.equals(manifestUrl, BuildVars.LUMA_UPDATE_MANIFEST_URL)) {
            requestManifest(generation, manifestUrl, true);
            return;
        }
        checking = false;
        checkingTask = null;
        if (checkTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(checkTimeout);
            checkTimeout = null;
        }
        // Drain before notifying observers: reentrant checks own their callbacks.
        ArrayList<Runnable> completions = takeCheckCompletions();
        if (!valid) {
            lastError = LocaleController.getString(TextUtils.isEmpty(response)
                    ? R.string.LumaUpdateCheckFailed : R.string.LumaUpdateManifestInvalid);
        } else {
            boolean cancelDownload;
            if (newVersionCode > getCurrentVersionCode()) {
                boolean changed = newVersionCode != versionCode || !TextUtils.equals(newSha256, sha256);
                if (changed) deleteDownloadedFile();
                version = newVersion;
                versionCode = newVersionCode;
                fileUrl = newFileUrl;
                sha256 = newSha256;
                changelog = newChangelog;
                cancelDownload = changed;
            } else {
                clearPendingUpdate(true);
                cancelDownload = true;
            }
            lastCheck = System.currentTimeMillis();
            save();
            // Download listeners can change the source or start another check.
            // Commit all state before invoking them, just like setManifestUrl.
            if (cancelDownload) cancelDownloadingUpdate();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        runCheckCompletions(completions);
    }

    public BetaUpdate getUpdate() {
        if (!LumaBuildPolicy.allowsBuiltInUpdates() || versionCode <= getCurrentVersionCode() || TextUtils.isEmpty(version)) {
            return null;
        }
        return new BetaUpdate(version, versionCode, changelog) {
            @Override
            public boolean higherThan(BetaUpdate update) {
                // Lunagram owns a monotonically increasing Android version code.
                // A marketing-name reset (12.10.x -> 1.0.0) is not a downgrade.
                // Keep upstream BetaUpdate's name policy unchanged for other channels.
                return update == null || versionCode > update.versionCode;
            }
        };
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
        if (!LumaBuildPolicy.allowsBuiltInUpdates()) {
            notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateSourceMissing));
            return;
        }
        File existing = getDownloadedFile();
        if (existing != null) {
            notifyDownloadFinished(existing, null);
            return;
        }
        if (downloading) {
            if (listener != null) {
                try {
                    listener.onProgress(downloadingProgress);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return;
        }
        if (getUpdate() == null || !isHttps(fileUrl) || TextUtils.isEmpty(sha256)) {
            notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateManifestInvalid));
            return;
        }

        File directory = updateDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateDownloadFailed));
            return;
        }
        final int expectedVersion = versionCode;
        final String expectedSha256 = sha256;
        final String downloadUrl = fileUrl;
        final File destination;
        try {
            // AsyncTask.cancel(false) can leave its worker alive until the next read.
            // Never allow that old worker to write/delete a retry's APK.
            destination = File.createTempFile("luma-update-" + expectedVersion + "-", ".apk.part", directory);
        } catch (IOException e) {
            FileLog.e(e);
            notifyDownloadFinished(null, LocaleController.getString(R.string.LumaUpdateDownloadFailed));
            return;
        }

        downloading = true;
        downloadingProgress = 0f;
        lastNotifiedProgress = -1f;
        lastProgressNotificationTime = 0L;
        lastError = null;
        final int generation = ++downloadGeneration;
        HttpGetFileTask task = new HttpGetFileTask(downloadedFile -> {
            if (generation != downloadGeneration) {
                // This callback means the old transport is done; no active worker owns its staging file.
                LumaUpdateFiles.deleteStaging(directory, destination);
                return;
            }
            if (downloadedFile == null) {
                downloading = false;
                downloadingTask = null;
                LumaUpdateFiles.deleteStaging(directory, destination);
                lastError = LocaleController.getString(R.string.LumaUpdateDownloadFailed);
                notifyDownloadFinished(null, lastError);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                String verificationError = verifyDownloadedApk(downloadedFile, expectedSha256, expectedVersion);
                AndroidUtilities.runOnUIThread(() -> {
                    if (generation != downloadGeneration) {
                        LumaUpdateFiles.deleteStaging(directory, destination);
                        return;
                    }
                    downloading = false;
                    downloadingTask = null;
                    File verifiedFile = new File(directory, "luma-update-" + expectedVersion + ".apk");
                    if (verificationError == null && (!verifiedFile.exists() || LumaUpdateFiles.delete(directory, verifiedFile))
                            && downloadedFile.renameTo(verifiedFile)) {
                        path = verifiedFile.getAbsolutePath();
                        downloadingProgress = 1f;
                        save();
                        notifyDownloadFinished(verifiedFile, null);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        LumaUpdateFiles.deleteStaging(directory, destination);
                        lastError = verificationError != null ? verificationError
                                : LocaleController.getString(R.string.LumaUpdateDownloadFailed);
                        notifyDownloadFinished(null, lastError);
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                });
            });
        }, progress -> {
            if (generation != downloadGeneration) {
                return;
            }
            downloadingProgress = Math.max(downloadingProgress, Math.min(1f, progress));
            long now = SystemClock.uptimeMillis();
            if (downloadingProgress >= 1f || lastNotifiedProgress < 0f || now - lastProgressNotificationTime >= 250L) {
                lastNotifiedProgress = downloadingProgress;
                lastProgressNotificationTime = now;
                notifyDownloadProgress();
            }
        }).setDestFile(destination)
                .setMaxSize(MAX_APK_SIZE)
                .setOverrideExtension("apk")
                .setAllowedHosts("github.com", "githubusercontent.com");
        downloadingTask = task;
        notifyDownloadProgress();
        // A listener can cancel/replace this attempt from the initial progress callback.
        if (generation == downloadGeneration && downloading && downloadingTask == task) {
            task.execute(downloadUrl);
        } else {
            LumaUpdateFiles.deleteStaging(directory, destination);
        }
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
        return LumaBuildPolicy.allowsBuiltInUpdates() && downloading;
    }

    public float getDownloadingProgress() {
        return LumaBuildPolicy.allowsBuiltInUpdates() ? downloadingProgress : 0f;
    }

    public File getDownloadedFile() {
        if (!LumaBuildPolicy.allowsBuiltInUpdates() || TextUtils.isEmpty(path)) {
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
        if (!LumaBuildPolicy.allowsBuiltInUpdates()) return false;
        File file = getDownloadedFile();
        if (activity == null || file == null) {
            return false;
        }
        if (!ApplicationLoader.applicationLoaderInstance.checkApkInstallPermissions(activity)) {
            return false;
        }
        return AndroidUtilities.openForView(file, "Lunagram.apk", "application/vnd.android.package-archive", activity, null, false);
    }

    private void notifyDownloadProgress() {
        final int generation = downloadGeneration;
        final float progress = downloadingProgress;
        ArrayList<DownloadListener> snapshot = new ArrayList<>(downloadListeners);
        for (DownloadListener listener : snapshot) {
            if (generation != downloadGeneration || !downloading) return;
            try {
                listener.onProgress(progress);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
    }

    private void notifyDownloadFinished(File file, String error) {
        ArrayList<DownloadListener> snapshot = new ArrayList<>(downloadListeners);
        downloadListeners.clear();
        for (DownloadListener listener : snapshot) {
            try {
                listener.onFinished(file, error);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private String verifyDownloadedApk(File file, String expectedSha256, int expectedVersion) {
        try {
            if (!TextUtils.equals(expectedSha256, calculateSha256(file))) {
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
            if (archiveVersion != expectedVersion || archiveVersion <= getCurrentVersionCode()) {
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
                LumaUpdateFiles.delete(updateDirectory(), new File(path));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        path = null;
    }

    private File updateDirectory() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
    }
}
