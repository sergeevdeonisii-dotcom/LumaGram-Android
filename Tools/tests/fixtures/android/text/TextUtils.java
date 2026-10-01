package android.text;
public final class TextUtils { public static boolean isEmpty(CharSequence value) { return value == null || value.length() == 0; } public static boolean equals(CharSequence a, CharSequence b) { return a == b || a != null && b != null && a.toString().equals(b.toString()); } }
