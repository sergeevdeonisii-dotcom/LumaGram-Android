package org.telegram.messenger;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared one-shot/identity/filesystem guards for local exports. No server or UI actions. */
final class LumaExportSession {
    private final int account;
    private final long owner;
    private final AtomicBoolean started = new AtomicBoolean(false);

    LumaExportSession(int account) {
        this.account = account;
        owner = UserConfig.getInstance(account).getClientUserId();
    }

    boolean hasOwner() { return owner > 0; }
    boolean isCurrent() { return hasOwner() && owner == UserConfig.getInstance(account).getClientUserId(); }

    boolean startOnce(AtomicBoolean cancelled, AtomicBoolean terminal) {
        return !cancelled.get() && !terminal.get() && started.compareAndSet(false, true);
    }

    boolean executeIfActive(ExecutorService worker, AtomicBoolean cancelled, AtomicBoolean terminal, Runnable task) {
        // Terminal transitions/shutdown in both managers use this same lock. A late
        // history/file callback cannot enqueue onto the already closed executor.
        synchronized (worker) {
            if (!isCurrent() || cancelled.get() || terminal.get() || worker.isShutdown()) return false;
            worker.execute(task);
            return true;
        }
    }

    static File createDirectory(File root) throws IOException {
        File directory = new File(root, ".session_" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("Could not create export session directory");
        return directory;
    }

    static File reserveFile(File directory, String name) throws IOException {
        File result = new File(directory, name);
        if (result.createNewFile()) return result;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; n < 10_000; n++) {
            result = new File(directory, base + " (" + n + ")" + extension);
            if (result.createNewFile()) return result;
        }
        result = new File(directory, base + "_" + UUID.randomUUID() + extension);
        if (result.createNewFile()) return result;
        throw new IOException("Could not reserve export output");
    }
}
