package org.telegram.messenger;

import android.content.Context;
import com.google.android.gms.common.GoogleSignatureVerifier;
import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.util.*;

/** Offline tests execute the real caller validator with synthetic PackageManager/cert results. */
public final class MediaBrowser100Test {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void callers() {
        Context context = new Context();
        String[] known = { "com.google.android.projection.gearhead", "com.google.android.mediasimulator",
                "com.android.car.media", "com.android.car.carlauncher", "com.google.android.car.kitchensink",
                "com.google.android.wearable.app", "com.google.android.wearable.media.sessions",
                "com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.staticplugins",
                "com.google.android.bluetooth" };
        int uid = 12000;
        for (String name : known) {
            context.packages.owners.put(uid, new String[] {name});
            check(!PackageValidator.isKnownCaller(context, name, uid), "spoofed known package denied: " + name);
            GoogleSignatureVerifier.signed.add(name);
            check(PackageValidator.isKnownCaller(context, name, uid), "Google certificate accepted: " + name);
            check(!PackageValidator.isKnownCaller(context, name, uid + 1), "certificate cannot bypass UID ownership");
            GoogleSignatureVerifier.signed.clear();
            context.packages.platformSigned.add(name);
            check(PackageValidator.isKnownCaller(context, name, uid), "platform-signed known client accepted");
            context.packages.platformSigned.clear();
            GoogleSignatureVerifier.fail = true;
            check(!PackageValidator.isKnownCaller(context, name, uid), "unavailable verifier fails closed");
            GoogleSignatureVerifier.fail = false;
        }
        String custom = "example.media.controller";
        context.packages.owners.put(uid, new String[] {"example.shared", custom});
        check(!PackageValidator.isKnownCaller(context, custom, uid), "arbitrary ordinary client denied");
        GoogleSignatureVerifier.signed.add(custom);
        check(!PackageValidator.isKnownCaller(context, custom, uid), "certificate alone does not expand allowlist");
        GoogleSignatureVerifier.signed.clear();
        for (String permission : new String[] {"android.permission.MEDIA_CONTENT_CONTROL", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"}) {
            context.packages.granted.add(custom + ":" + permission);
            check(PackageValidator.isKnownCaller(context, custom, uid), "existing privileged holder accepted");
            check(!PackageValidator.isKnownCaller(context, custom, uid + 1), "privilege cannot bypass UID ownership");
            context.packages.granted.clear();
        }
        for (int privilegedUid : new int[] {1000, 10001}) {
            check(!PackageValidator.isKnownCaller(context, custom, privilegedUid), "claimed self/system UID needs ownership");
            context.packages.owners.put(privilegedUid, new String[] {custom});
            check(PackageValidator.isKnownCaller(context, custom, privilegedUid), "real self/system accepted");
        }
        check(!PackageValidator.isKnownCaller(context, null, uid), "null package denied");
        check(!PackageValidator.isKnownCaller(context, "", uid), "empty package denied");
        check(!PackageValidator.isKnownCaller(context, custom, -1), "negative UID denied");
        context.packages.fail = true;
        check(!PackageValidator.isKnownCaller(context, custom, uid), "package lookup error fails closed");
    }

    private static void visibility() {
        for (int size : new int[] {0, 1, 100, Integer.MAX_VALUE}) {
            for (long index : new long[] {Long.MIN_VALUE, -1, 0, 1, 99, 100, Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
                check(LumaMediaBrowserPolicy.validQueueIndex(index, size) == (index >= 0 && index < size), "queue index checked before narrowing");
            }
        }
        for (long owner : new long[] {-1, 0, 42}) {
            for (long dialog : new long[] {0, 123, -456}) {
                for (int flags = 0; flags < 8; flags++) {
                    boolean encrypted = (flags & 1) != 0, hidden = (flags & 2) != 0, locked = (flags & 4) != 0;
                    boolean expected = owner > 0 && dialog != 0 && flags == 0;
                    check(LumaMediaBrowserPolicy.canExpose(owner, dialog, encrypted, hidden, locked) == expected,
                            "external metadata policy");
                }
            }
        }
        // No UI-unlock argument exists: hidden is always excluded even after successful vault unlock.
        for (boolean vaultUiUnlocked : new boolean[] {false, true}) {
            check(!LumaMediaBrowserPolicy.canExpose(42, 123, false, true, false), "hidden regardless of UI unlock=" + vaultUiUnlocked);
        }
        for (int account : new int[] {0, 1}) {
            for (long owner : new long[] {0, 42, 77}) {
                for (long generation : new long[] {1, 2, 3}) {
                    for (int selected : new int[] {0, 1}) {
                        for (long liveOwner : new long[] {0, 42, 77}) {
                            for (long liveGeneration : new long[] {1, 2, 3}) {
                                boolean expected = owner > 0 && owner == liveOwner && account == selected && generation == liveGeneration;
                                check(LumaMediaBrowserPolicy.sameOwner(account, owner, generation, selected, liveOwner, liveGeneration) == expected,
                                        "delayed callbacks bound to account, identity and generation");
                            }
                        }
                    }
                }
            }
        }
        check(!LumaMediaBrowserPolicy.sameOwner(0, 42, 1, 0, 42, 3), "switch away/back invalidates old request");
        check(!LumaMediaBrowserPolicy.sameOwner(0, 42, 1, 0, 77, 1), "slot reuse cannot inherit old metadata");
    }

    private static void parseProduction(String[] paths) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    Arrays.asList("-proc:none"), null, files.getJavaFileObjects(paths));
            task.parse(); // Syntax only: deliberately no Android build, generated R, or dependency download.
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                check(diagnostic.getKind() != Diagnostic.Kind.ERROR, "production syntax: " + diagnostic);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        callers();
        visibility();
        parseProduction(args);
        System.out.println("PASS MediaBrowser 1.0.0: " + checks + " assertions; validator and privacy predicates executed, integration Java parsed.");
    }
}
