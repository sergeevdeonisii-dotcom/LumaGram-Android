package org.telegram.messenger;

/** Compatibility entry point: credentials stay local, never in OS backups. */
public final class BackupAgent {
    private BackupAgent() {}

    public static void requestBackup() {
        // Existing authentication/launch callers can retain this no-op. There
        // is no registered backup agent, and all OS extraction domains are
        // excluded by the manifest/rules. Explicit user exports are separate.
    }
}
