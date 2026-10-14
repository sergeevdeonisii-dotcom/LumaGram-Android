package org.telegram.messenger;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;

import java.util.IdentityHashMap;

/** Device-local presentation only. Never creates or modifies message entities. */
public final class LunagramTypography {
    public static final int FAMILY_SYSTEM = 0, FAMILY_SANS = 1, FAMILY_SERIF = 2,
            FAMILY_MONO = 3, FAMILY_CONDENSED = 4;
    public static final int WEIGHT_DEFAULT = 0, WEIGHT_MEDIUM = 1, WEIGHT_BOLD = 2;
    private static final String[] FAMILIES = {null, "sans-serif", "serif", "monospace", "sans-serif-condensed"};
    private static boolean loaded;
    private static int family, weight, activeFamily, activeWeight, revision;
    private static final IdentityHashMap<Typeface, Typeface> cache = new IdentityHashMap<>();
    private static final IdentityHashMap<Typeface, Integer> roles = new IdentityHashMap<>();

    private LunagramTypography() { }

    private static void load() {
        if (loaded || ApplicationLoader.applicationContext == null) return;
        SharedPreferences p = preferences();
        try { family = clampFamily(p.getInt("family", 0)); } catch (ClassCastException ignored) { family = 0; }
        try { weight = clampWeight(p.getInt("weight", 0)); } catch (ClassCastException ignored) { weight = 0; }
        activeFamily = family;
        activeWeight = weight;
        loaded = true;
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("lunagram_typography", 0);
    }

    public static int clampFamily(int value) { return value >= 0 && value < FAMILIES.length ? value : 0; }
    public static int clampWeight(int value) { return value >= 0 && value <= WEIGHT_BOLD ? value : 0; }
    public static synchronized int family() { load(); return family; }
    public static synchronized int weight() { load(); return weight; }
    public static synchronized int revision() { load(); return revision; }
    public static synchronized boolean isDefault() { load(); return activeFamily == 0 && activeWeight == 0; }
    public static synchronized boolean requiresRestart() { load(); return family != activeFamily || weight != activeWeight; }

    public static synchronized boolean set(int nextFamily, int nextWeight) {
        load();
        nextFamily = clampFamily(nextFamily);
        nextWeight = clampWeight(nextWeight);
        if (family == nextFamily && weight == nextWeight) return false;
        if (ApplicationLoader.applicationContext == null) return false;
        family = nextFamily;
        weight = nextWeight;
        preferences().edit().putInt("family", family).putInt("weight", weight).apply();
        revision++;
        return true;
    }

    public static boolean reset() { return set(0, 0); }

    /** The code role is deliberately not remapped, including bold/italic monospace. */
    private static boolean isCode(Typeface source) {
        if (source == null) return false;
        for (int style = 0; style <= 3; style++) {
            if (source.equals(Typeface.create(Typeface.MONOSPACE, style))) return true;
        }
        return false;
    }

    private static boolean isSystemFace(Typeface source) {
        if (source == null) return true;
        for (int style = 0; style <= 3; style++) {
            if (source.equals(Typeface.create(Typeface.DEFAULT, style))
                    || source.equals(Typeface.create(Typeface.SANS_SERIF, style))) return true;
        }
        return false;
    }

    /** Only native UI faces and our known Roboto roles are eligible. Editor/artwork fonts stay intact. */
    public static synchronized Typeface resolve(Typeface source) {
        load();
        if (activeFamily == 0 && activeWeight == 0) return source;
        if (cache.containsKey(source)) return cache.get(source);
        if (isCode(source) || !isSystemFace(source) && !roles.containsKey(source)) {
            cache.put(source, source);
            return source;
        }
        int role = roles.containsKey(source) ? roles.get(source) : source != null && source.isBold() ? 700 : 400;
        Typeface result = create(source, role, activeFamily, activeWeight);
        cache.put(source, result);
        return result;
    }

    private static Typeface create(Typeface source, int role, int selectedFamily, int selectedWeight) {
        int selected = selectedWeight == WEIGHT_BOLD ? 700 : selectedWeight == WEIGHT_MEDIUM ? 500 : 400;
        int resolvedWeight = Math.max(role, selected);
        boolean italic = source != null && source.isItalic();
        Typeface base = selectedFamily == FAMILY_SYSTEM ? Typeface.DEFAULT : Typeface.create(FAMILIES[selectedFamily], Typeface.NORMAL);
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(base, resolvedWeight, italic);
        return Typeface.create(base, (resolvedWeight >= 600 ? Typeface.BOLD : Typeface.NORMAL) | (italic ? Typeface.ITALIC : 0));
    }

    /** Settings preview only; active fonts remain immutable until the next process launch. */
    public static synchronized Typeface preview(boolean heading) {
        load();
        if (family == 0 && weight == 0) return heading ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        return create(null, heading ? 700 : 400, family, weight);
    }

    public static synchronized Typeface resolveAsset(String path, Typeface source) {
        if (source == null) return null;
        boolean registered = roles.containsKey(source);
        if ("fonts/rmedium.ttf".equals(path) || "fonts/rmediumitalic.ttf".equals(path)) roles.put(source, 700);
        else if ("fonts/rextrabold.ttf".equals(path)) roles.put(source, 800);
        else if ("fonts/ritalic.ttf".equals(path)) roles.put(source, 400);
        else if ("fonts/rcondensedbold.ttf".equals(path)) roles.put(source, 700);
        else return source;
        if (!registered) cache.remove(source);
        return resolve(source);
    }

    public static synchronized Typeface resolveSystemMedium(Typeface source) {
        if (source != null && !roles.containsKey(source)) {
            roles.put(source, 500);
            cache.remove(source);
        }
        return resolve(source);
    }

    public static synchronized TextPaint textPaint(int flags) {
        TextPaint paint = new TextPaint(flags);
        paint.setTypeface(resolve(null));
        return paint;
    }
}
