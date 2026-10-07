package org.telegram.messenger;

import android.content.pm.PackageManager;
import org.json.JSONObject;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Compiles the production controller; all HTTP/package-manager responses are local fixtures. */
public final class LumaUpdaterAudit73Test {
    private static int checks;
    private static final byte[] APK = "signed-fixture-apk".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        ApplicationLoader.applicationContext.root = new File(args[0]);
        File directory = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        check(directory.mkdirs() || directory.isDirectory(), "sandbox created");
        LumaUpdaterController controller = LumaUpdaterController.getInstance();
        Utilities.globalQueue.drain();
        manifest("available", 71500);
        checkUpdate(controller, "available");

        controller.downloadUpdate();
        HttpGetFileTask first = download();
        Files.write(first.destination.toPath(), APK);
        controller.cancelDownloadingUpdate();
        controller.downloadUpdate();
        HttpGetFileTask retry = download();
        check(!first.destination.equals(retry.destination), "cancel/retry workers must not share a writable APK path");
        Files.write(retry.destination.toPath(), APK);
        first.delayedCancelCleanup();
        check(retry.destination.isFile(), "late old cancellation cannot delete the new attempt");
        retry.finish();
        Utilities.globalQueue.drain();
        check(controller.getDownloadedFile() != null && controller.getDownloadedFile().isFile(), "verified retry is accepted");
        check(controller.getDownloadedFile().getName().equals("luma-update-71500.apk"), "installer receives the final versioned filename");

        manifest("withdrawn", PackageManager.installedVersion);
        checkUpdate(controller, "withdrawn");
        checkUpdate(controller, "available");
        controller.downloadUpdate();
        HttpGetFileTask withdrawn = download();
        checkUpdate(controller, "withdrawn");
        check(withdrawn.cancelled && !controller.isDownloading(), "withdrawn release cancels its active download");
        Files.write(withdrawn.destination.toPath(), APK);
        withdrawn.finish();
        Utilities.globalQueue.drain();
        check(controller.getDownloadedFile() == null, "withdrawn release cannot become installable after a stale callback");

        checkUpdate(controller, "available");
        int beforeReentry = HttpGetFileTask.requests.size();
        AtomicInteger cancelled = new AtomicInteger();
        controller.addDownloadListener(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float progress) { controller.cancelDownloadingUpdate(); }
            public void onFinished(File file, String error) { cancelled.incrementAndGet(); }
        });
        AtomicInteger progressAfterCancel = new AtomicInteger();
        controller.downloadUpdate(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float progress) { progressAfterCancel.incrementAndGet(); }
            public void onFinished(File file, String error) { }
        });
        check(HttpGetFileTask.requests.size() == beforeReentry && !controller.isDownloading(), "progress cancellation prevents transport execution");
        check(cancelled.get() == 1, "progress cancellation finishes exactly once");
        check(progressAfterCancel.get() == 0, "cancelled attempt cannot deliver progress after its finish callback");

        AtomicInteger surviving = new AtomicInteger();
        controller.addDownloadListener(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float progress) { throw new IllegalStateException("listener fixture"); }
            public void onFinished(File file, String error) { throw new IllegalStateException("listener fixture"); }
        });
        controller.downloadUpdate(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float progress) { surviving.incrementAndGet(); }
            public void onFinished(File file, String error) { surviving.incrementAndGet(); }
        });
        download().fail();
        check(surviving.get() == 2 && !controller.isDownloading(), "one failed UI listener cannot strand other dialogs");

        controller.downloadUpdate();
        HttpGetFileTask verification = download();
        Files.write(verification.destination.toPath(), APK);
        verification.finish();
        controller.setManifestUrl("https://replacement.example/latest.json");
        Utilities.globalQueue.drain();
        check(controller.getDownloadedFile() == null && !verification.destination.exists(), "source switch cleans completed stale staging APK");
        HttpGetTask.requests.get(HttpGetTask.requests.size() - 1).deliver("available");

        AtomicInteger sourceReentry = new AtomicInteger();
        controller.downloadUpdate(new LumaUpdaterController.DownloadListener() {
            public void onProgress(float progress) { }
            public void onFinished(File file, String error) {
                check(controller.getUpdate() == null, "source-change cancellation listener sees cleared old release");
                controller.checkForUpdate(true, null);
                sourceReentry.incrementAndGet();
            }
        });
        controller.setManifestUrl("https://next.example/latest.json");
        check(sourceReentry.get() == 1, "source replacement finishes old download exactly once");
        HttpGetTask reentrantCheck = HttpGetTask.requests.get(HttpGetTask.requests.size() - 1);
        check(reentrantCheck.url.startsWith("https://next.example/"), "reentrant cancellation listener checks replacement source, not the old one");
        reentrantCheck.deliver("available");

        controller.downloadUpdate();
        HttpGetFileTask pending = download();
        Files.write(pending.destination.toPath(), APK);
        pending.finish();
        manifest("newer", 71510);
        checkUpdate(controller, "newer");
        controller.downloadUpdate();
        HttpGetFileTask newer = download();
        Files.write(newer.destination.toPath(), APK);
        Utilities.globalQueue.drain();
        check(!pending.destination.exists() && newer.destination.exists() && controller.isDownloading(), "stale verifier cleans only its own attempt, preserving a newer download");
        PackageManager.archiveVersion = 71510;
        newer.finish();
        Utilities.globalQueue.drain();
        check(controller.getDownloadedFile() != null && controller.getDownloadedFile().getName().equals("luma-update-71510.apk"), "new release passes version verification and remains installable");
        checkUpdate(controller, "withdrawn");

        checkUpdate(controller, "available");
        controller.downloadUpdate();
        HttpGetFileTask corrupt = download();
        Files.write(corrupt.destination.toPath(), "corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        corrupt.finish();
        Utilities.globalQueue.drain();
        check(!corrupt.destination.exists() && controller.getDownloadedFile() == null
                && controller.getLastError().equals(LocaleController.getString(R.string.LumaUpdateHashMismatch)), "hash verification remains mandatory and removes rejected staging APK");

        controller.downloadUpdate();
        HttpGetFileTask wrongVersion = download();
        Files.write(wrongVersion.destination.toPath(), APK);
        wrongVersion.finish();
        Utilities.globalQueue.drain();
        check(!wrongVersion.destination.exists() && controller.getDownloadedFile() == null
                && controller.getLastError().equals(LocaleController.getString(R.string.LumaUpdateVersionMismatch)), "archive version verification remains mandatory after snapshot fix");

        File stray = new File(directory, "luma-update-71500-123.apk.part");
        File unrelated = new File(directory, "user-backup.apk.part");
        File nested = new File(new File(directory, "nested"), "luma-update-71500-456.apk.part");
        nested.getParentFile().mkdirs();
        Files.write(stray.toPath(), APK); Files.write(unrelated.toPath(), APK); Files.write(nested.toPath(), APK);
        LumaUpdateFiles.class.getMethod("cleanupAbandonedDownloads", File.class).invoke(null, directory);
        check(!stray.exists() && unrelated.exists() && nested.exists(), "startup cleanup is limited to own direct-child staging files");
        check(controller.getUpdate() != null, "a source replacement still reports its release");
        System.out.println("Updater .73 regressions: " + checks + " passed");
    }

    private static void manifest(String token, int code) throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("version", "test-" + code); values.put("version_code", code);
        values.put("file_url", "https://github.com/fixture/lunagram.apk");
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(APK)) sha.append(String.format("%02x", b & 255));
        values.put("sha256", sha.toString()); JSONObject.responses.put(token, values);
    }
    private static void checkUpdate(LumaUpdaterController controller, String token) {
        controller.checkForUpdate(true, null);
        HttpGetTask.requests.get(HttpGetTask.requests.size() - 1).deliver(token);
    }
    private static HttpGetFileTask download() { return HttpGetFileTask.requests.get(HttpGetFileTask.requests.size() - 1); }
    private static void check(boolean result, String text) { if (!result) throw new AssertionError(text); ++checks; }
}
