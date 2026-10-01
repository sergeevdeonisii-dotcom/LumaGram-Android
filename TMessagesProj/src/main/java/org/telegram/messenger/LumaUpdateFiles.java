package org.telegram.messenger;

import java.io.File;
import java.io.IOException;

/** Restricts installer cleanup to our own versioned APKs, including legacy orphan files. */
public final class LumaUpdateFiles {
    private LumaUpdateFiles() { }

    public static void cleanupInstalled(File directory, int installedVersion) {
        if (installedVersion <= 0) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            long version = version(file);
            if (version > 0 && version <= installedVersion) delete(directory, file);
        }
    }

    public static boolean delete(File directory, File file) {
        try {
            if (version(file) > 0 && file.isFile()
                    && directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
                return file.delete();
            }
        } catch (IOException ignored) { }
        return false;
    }

    private static long version(File file) {
        String name = file.getName();
        if (!name.matches("luma-update-[0-9]+\\.apk")) return -1;
        try {
            return Long.parseLong(name.substring("luma-update-".length(), name.length() - 4));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
