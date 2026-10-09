package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

import com.google.android.gms.common.GoogleSignatureVerifier;

import java.util.HashSet;
import java.util.Set;

/**
 * Validates which callers are allowed to use the MediaBrowserService.
 * Package names are routing hints, never proof of identity. Known media clients
 * must have a Google/platform certificate or an existing privileged permission.
 * Declaring a notification-listener service is not itself a permission grant.
 */
public final class PackageValidator {

    private PackageValidator() {}

    private static final Set<String> KNOWN_PACKAGES = new HashSet<>();

    static {
        // Android Auto (projection mode)
        KNOWN_PACKAGES.add("com.google.android.projection.gearhead");
        // Auto media simulator (DHU)
        KNOWN_PACKAGES.add("com.google.android.mediasimulator");
        // Android Automotive OS embedded media client
        KNOWN_PACKAGES.add("com.android.car.media");
        KNOWN_PACKAGES.add("com.android.car.carlauncher");
        KNOWN_PACKAGES.add("com.google.android.car.kitchensink");
        // Wear OS
        KNOWN_PACKAGES.add("com.google.android.wearable.app");
        KNOWN_PACKAGES.add("com.google.android.wearable.media.sessions");
        // Google Assistant
        KNOWN_PACKAGES.add("com.google.android.googlequicksearchbox");
        KNOWN_PACKAGES.add("com.google.android.apps.gsa.staticplugins");
        // Bluetooth media browser
        KNOWN_PACKAGES.add("com.google.android.bluetooth");
    }

    public static boolean isKnownCaller(Context context, String callerPackageName, int callerUid) {
        if (callerPackageName == null || callerPackageName.isEmpty() || callerUid < 0) return false;
        try {
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(callerUid);
            boolean ownsPackage = false;
            if (packages != null) {
                for (String name : packages) {
                    if (callerPackageName.equals(name)) {
                        ownsPackage = true;
                        break;
                    }
                }
            }
            if (!ownsPackage) return false;
            if (callerUid == Process.SYSTEM_UID || callerUid == Process.myUid()) return true;
            if (hasPermission(context, callerPackageName)) return true;
            if (!KNOWN_PACKAGES.contains(callerPackageName)) return false;
            return pm.checkSignatures("android", callerPackageName) == PackageManager.SIGNATURE_MATCH
                    || GoogleSignatureVerifier.getInstance(context).isPackageGoogleSigned(callerPackageName);
        } catch (Exception | LinkageError ignored) {
            // Missing package, certificate verifier or permission information fails closed.
            return false;
        }
    }

    private static boolean hasPermission(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            int contentControl = pm.checkPermission(
                    "android.permission.MEDIA_CONTENT_CONTROL", packageName);
            if (contentControl == PackageManager.PERMISSION_GRANTED) return true;
            int notifListener = pm.checkPermission(
                    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", packageName);
            if (notifListener == PackageManager.PERMISSION_GRANTED) return true;
        } catch (Throwable ignore) {
        }
        return false;
    }
}
