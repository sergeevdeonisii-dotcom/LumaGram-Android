package com.google.android.gms.common;
import android.content.Context;
import java.util.*;
public final class GoogleSignatureVerifier {
    private static final GoogleSignatureVerifier INSTANCE = new GoogleSignatureVerifier();
    public static final Set<String> signed = new HashSet<>();
    public static boolean fail;
    public static GoogleSignatureVerifier getInstance(Context context) {
        if (fail) throw new IllegalStateException("fixture");
        return INSTANCE;
    }
    public boolean isPackageGoogleSigned(String name) { return signed.contains(name); }
}
