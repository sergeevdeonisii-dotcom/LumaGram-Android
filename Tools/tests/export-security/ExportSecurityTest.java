package org.telegram.messenger;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real session + account exporter with fake Telegram child/network/UI queue, no device claims. */
public final class ExportSecurityTest {
    private static int checks;
    private static String sourceMutation = "";
    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            sourceMutation = args[2];
            sourceWiring(new File(args[1]));
            return;
        }
        File sandbox = new File(args[0]);
        check(sandbox.mkdirs() || sandbox.isDirectory(), "isolated sandbox");
        ApplicationLoader.applicationContext.root = sandbox;
        identity();
        for (int mode = 0; mode < 5; mode++) completedArchive(mode);
        sourceWiring(new File(args[1]));
        System.out.println("PASS " + checks + " export security checks: actual session/account worker + queued completions; UI/provider source contracts, not Android lifecycle/MediaStore tests.");
    }

    private static void identity() {
        UserConfig.ids[0] = 7001;
        LumaExportSession session = new LumaExportSession(0);
        check(session.isCurrent(), "initial owner");
        LunagramSubscriptionAccess.setTier(0, LunagramFeatureCatalog.Tier.FREE);
        check(session.isCurrent(), "identity remains usable when paid grant expires");
        UserConfig.ids[0] = 0;
        LunagramSubscriptionClient.epochs[0]++;
        check(!session.isCurrent(), "logout invalidates");
        UserConfig.ids[0] = 7001;
        check(!session.isCurrent(), "same-owner re-login cannot revive stale session");
        UserConfig.ids[0] = 7002;
        check(!session.isCurrent(), "different owner cannot revive stale session");
        check(!new LumaExportSession(-1).isCurrent(), "negative slot rejected without indexing");
        check(!new LumaExportSession(UserConfig.MAX_ACCOUNT_COUNT).isCurrent(), "oversized slot rejected");
        LunagramSubscriptionAccess.reset();
    }

    private static void completedArchive(int mode) throws Exception {
        UserConfig.ids[0] = 8000 + mode;
        LunagramSubscriptionClient.epochs[0]++;
        LunagramSubscriptionAccess.reset();
        LumaAccountExportManager.Config config = new LumaAccountExportManager.Config();
        config.accountName = "test owner";
        config.chats.add(new LumaAccountExportManager.ChatSpec(42, "test chat", "private", false));
        File[] completed = {null};
        int[] cancelled = {0};
        LumaAccountExportManager manager = new LumaAccountExportManager(config, new LumaAccountExportManager.Listener() {
            public void onProgress(LumaAccountExportManager.Progress p) {}
            public void onComplete(File f, int a, int b, int c, int d) { completed[0] = f; }
            public void onError(String message, Throwable e) { throw new AssertionError(message, e); }
            public void onCancelled() { cancelled[0]++; }
        });
        manager.start();
        File child = new File(ApplicationLoader.applicationContext.root, "child-" + mode + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(child.toPath()))) {
            zip.putNextEntry(new ZipEntry("messages.html"));
            zip.write("<html><head></head><body>private test</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        LumaChatExportManager.queuedCompletion.onComplete(child, new LumaChatExportManager.Progress());
        AndroidUtilities.next(); // child consumption posts exportNext; it schedules packaging
        Field workerField = LumaAccountExportManager.class.getDeclaredField("worker");
        workerField.setAccessible(true);
        check(((ExecutorService) workerField.get(manager)).awaitTermination(5, TimeUnit.SECONDS), "worker terminated before final callback");
        Field outputField = LumaAccountExportManager.class.getDeclaredField("outputDir");
        outputField.setAccessible(true);
        File output = (File) outputField.get(manager);
        check(output.getCanonicalFile().getParentFile().equals(ApplicationLoader.applicationContext.getCacheDir().getCanonicalFile()), "plaintext output only in private cache");
        check(!ApplicationLoader.applicationContext.getExternalCacheDir().exists(), "external storage never touched");
        if (mode == 1) LunagramSubscriptionAccess.setTier(0, LunagramFeatureCatalog.Tier.FREE);
        if (mode == 2) { UserConfig.ids[0] = 0; LunagramSubscriptionClient.epochs[0]++; }
        if (mode == 3) { LunagramSubscriptionClient.epochs[0]++; } // same owner re-login
        if (mode == 4) { UserConfig.ids[0]++; LunagramSubscriptionClient.epochs[0]++; }
        AndroidUtilities.drain();
        if (mode <= 1) {
            check(completed[0] != null && completed[0].isFile() && cancelled[0] == 0, "finished archive survives subscription expiry");
            check(manager.canReadCompletedExport(), "Save/Share boundary remains open for its original authenticated owner");
            LunagramSubscriptionClient.epochs[0]++;
            check(!manager.canReadCompletedExport(), "Save/Share boundary closes on same-owner new login");
            check(completed[0].isFile(), "revoking boundary does not delete user data as a side effect");
            check(completed[0].delete(), "test fixture cleanup of owned private archive");
        } else {
            check(completed[0] == null && cancelled[0] == 1, "stale completion never exposes data");
            check(output.listFiles() != null && output.listFiles().length == 0, "stale temporary archive discarded");
            check(!manager.canReadCompletedExport(), "stale Save/Share boundary closed");
        }
        check(!child.exists(), "child temporary copy removed");
    }

    private static String read(File root, String path) throws Exception {
        String text = Files.readString(new File(root, path).toPath()).replace("\r\n", "\n");
        if (path.endsWith("LumaChatExportSheet.java")) {
            if (sourceMutation.equals("save-callback")) text = text.replace("if (chatActivity.isFinished || !manager[0].canReadCompletedExport())", "if (chatActivity.isFinished)");
            if (sourceMutation.equals("share-boundary")) text = text.replace("if (!manager.canReadCompletedExport()) return;", "");
        }
        if (path.endsWith("LumaAccountExportActivity.java")) {
            if (sourceMutation.equals("scan-owner")) text = text.replace("generation == dialogScanGeneration && ownsScreen()", "generation == dialogScanGeneration");
            if (sourceMutation.equals("save-owner")) text = text.replace("ownsScreen() && operation[0].canReadCompletedExport() && identity.isCurrent()", "ownsScreen()");
        }
        return text;
    }
    private static void sourceWiring(File repo) throws Exception {
        String base = "TMessagesProj/src/main/java/org/telegram/";
        String chat = read(repo, base + "messenger/LumaChatExportManager.java");
        String account = read(repo, base + "messenger/LumaAccountExportManager.java");
        for (String source : new String[]{chat, account}) {
            check(!source.contains("getExternalCacheDir()"), "no external-cache fallback for plaintext exports");
            check(source.contains("outputDir = new File(privateCache,"), "output rooted in private cache");
            check(source.contains("public boolean canReadCompletedExport() {\n        return session.isCurrent();\n    }"), "completed data access identity-only, independent of paid entitlement");
        }
        String sheet = read(repo, base + "ui/Components/LumaChatExportSheet.java");
        check(sheet.contains("if (!identity.isCurrent() || chatActivity.isFinished) return;"), "old format picker cannot start an export for a new login");
        check(sheet.contains("if (chatActivity.isFinished || !manager[0].canReadCompletedExport() || chatActivity.getParentActivity() == null)"), "identity gate before save starts");
        check(sheet.contains("uri -> {\n                        if (chatActivity.isFinished || !manager[0].canReadCompletedExport())"), "identity gate on delayed save callback");
        check(sheet.contains("if (chatActivity.isFinished || !manager.canReadCompletedExport() || chatActivity.getParentActivity() == null)"), "identity gate before share URI creation");
        check(sheet.contains("if (!manager.canReadCompletedExport()) return;\n            chatActivity.getParentActivity().startActivityForResult("), "identity rechecked at external URI grant boundary");
        String activity = read(repo, base + "ui/LumaAccountExportActivity.java");
        check(activity.contains("if (!ownsScreen() || !operation[0].canReadCompletedExport() || !identity.isCurrent() || getParentActivity() == null)"), "account completed save gate");
        check(activity.contains("if (ownsScreen() && operation[0].canReadCompletedExport() && identity.isCurrent())"), "account delayed save callback gate");
        check(activity.contains("if (screenIdentity == null) screenIdentity = LunagramMediaAccess.forAccount(currentAccount);"), "screen keeps its original account epoch");
        check(activity.contains("if (generation == dialogScanGeneration && ownsScreen()) handleDialogPage(response, error);"), "stale scan response cannot modify new owner or restarted scan");
        check(activity.contains("private void handleDialogPage(org.telegram.tgnet.TLObject response, TLRPC.TL_error error) {\n        if (!ownsScreen()) { cancelDialogScan(); return; }"), "identity gate precedes users/chats cache mutation");
        check(activity.contains("public void onFragmentDestroy() {\n        cancelDialogScan();\n        if (manager != null) manager.cancel();"), "destroy stops scan and pending export");
        check(activity.contains("if (manager == operation[0]) manager = null;"), "old callbacks cannot clear a newer operation");
        check(!sheet.contains("getContentResolver().delete(") && !activity.contains("getContentResolver().delete("), "saved user-owned copies never deleted on expiry/logout");
        String paths = read(repo, "TMessagesProj/src/main/res/xml/provider_paths.xml");
        check(paths.contains("name=\"lunagram_chat_exports\" path=\"luma_chat_exports/\""), "only chat export cache scope granted");
        check(paths.contains("name=\"lunagram_account_exports\" path=\"luma_account_exports/\""), "only account export cache scope granted");
        check(!paths.contains("<cache-path name=\"cache\" path=\".\""), "no broad private-cache grant added");
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
