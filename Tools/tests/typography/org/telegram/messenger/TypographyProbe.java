package org.telegram.messenger;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;

public final class TypographyProbe {
    private static int checks;
    private static Context context;
    private static int startupFamily, startupWeight;
    private static final String[] FAMILY_NAMES = {"sans-serif", "sans-serif", "serif", "monospace", "sans-serif-condensed"};

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void same(Object expected, Object actual, String label) {
        check(expected == actual, label + ": expected identity " + expected + ", got " + actual);
    }

    private static void face(Typeface value, String family, int weight, boolean italic, String label) {
        int expectedWeight = Build.VERSION.SDK_INT >= 28 ? weight : weight >= 600 ? 700 : 400;
        check(value != null && family.equals(value.family) && value.weight == expectedWeight
                && value.isItalic() == italic, label + ": got " + value);
    }

    private static boolean startupDefault() { return startupFamily == 0 && startupWeight == 0; }

    private static void initialState(String scenario, int seedFamily, int seedWeight) {
        context = new Context();
        context.preferences.values.put("family", seedFamily);
        context.preferences.values.put("weight", seedWeight);
        startupFamily = seedFamily;
        startupWeight = seedWeight;
        if ("wrong-types".equals(scenario)) {
            context.preferences.values.put("family", "serif");
            context.preferences.values.put("weight", true);
            startupFamily = startupWeight = 0;
        } else if ("wrong-range".equals(scenario)) {
            context.preferences.values.put("family", Integer.MAX_VALUE);
            context.preferences.values.put("weight", -1);
            startupFamily = startupWeight = 0;
        } else if ("early-context".equals(scenario)) {
            check(LunagramTypography.isDefault(), "missing context uses defaults");
            check(!LunagramTypography.set(2, 1), "missing context cannot pretend to persist");
            same(null, LunagramTypography.resolve(null), "missing context preserves native null face");
        }
        ApplicationLoader.applicationContext = context;
        check(LunagramTypography.family() == startupFamily, "stored family validates on load");
        check(LunagramTypography.weight() == startupWeight, "stored weight validates on load");
        check(LunagramTypography.isDefault() == startupDefault(), "active default reflects startup pair");
        check(!LunagramTypography.requiresRestart(), "startup selection does not require restart");
        check("lunagram_typography".equals(context.requestedName) && context.requestedMode == 0,
                "preferences use one private device-local namespace");
        check(context.preferences.writes == 0, "loading preferences does not write");
    }

    private static void validationAndPersistence() {
        for (int value = 0; value <= 4; value++)
            check(LunagramTypography.clampFamily(value) == value, "known family remains selectable " + value);
        for (int value = 0; value <= 2; value++)
            check(LunagramTypography.clampWeight(value) == value, "known weight remains selectable " + value);
        for (int value : new int[]{Integer.MIN_VALUE, -1, 5, 99, Integer.MAX_VALUE})
            check(LunagramTypography.clampFamily(value) == 0, "unknown family falls back " + value);
        for (int value : new int[]{Integer.MIN_VALUE, -1, 3, 99, Integer.MAX_VALUE})
            check(LunagramTypography.clampWeight(value) == 0, "unknown weight falls back " + value);
        LunagramTypography.reset();
        int revision = LunagramTypography.revision();
        int writes = context.preferences.writes;
        check(!LunagramTypography.set(99, -9), "invalid selections sanitize to default no-op");
        check(LunagramTypography.revision() == revision && context.preferences.writes == writes,
                "no-op preserves revision and avoids persistence");
        check(LunagramTypography.set(2, 1), "valid changed selection succeeds");
        check(LunagramTypography.revision() == revision + 1, "selection advances revision once");
        check(context.preferences.writes == writes + 1, "pair persists in one editor apply");
        check(Integer.valueOf(2).equals(context.preferences.values.get("family"))
                && Integer.valueOf(1).equals(context.preferences.values.get("weight")), "both choices persist");
        check(!LunagramTypography.set(2, 1), "same selection is a no-op");
        check(LunagramTypography.revision() == revision + 1 && context.preferences.writes == writes + 1,
                "same selection does not invalidate consumers");
        check(LunagramTypography.reset(), "reset reports a real change");
        check(!LunagramTypography.reset(), "reset is idempotent");
        check(LunagramTypography.requiresRestart() == !startupDefault(), "reset notice compares with active startup pair");
        check(LunagramTypography.isDefault() == startupDefault(), "reset does not silently replace active fonts");
        LunagramTypography.set(startupFamily, startupWeight);
        check(!LunagramTypography.requiresRestart(), "restoring startup selection clears restart notice");
    }

    private static void rolePreservation() {
        Typeface normal = Typeface.DEFAULT;
        Typeface italic = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);
        Typeface boldItalic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
        Typeface artwork = Typeface.create("artwork-font", Typeface.BOLD_ITALIC);
        Typeface medium = Typeface.create("asset-rmedium", Typeface.NORMAL);
        Typeface mediumItalic = Typeface.create("asset-rmediumitalic", Typeface.ITALIC);
        Typeface extraBold = Typeface.create("asset-rextrabold", Typeface.NORMAL);
        same(medium, LunagramTypography.resolve(medium), "unregistered asset is initially left untouched");
        if (startupDefault()) {
            same(null, LunagramTypography.resolve(null), "default null remains native default");
            for (Typeface value : new Typeface[]{normal, italic, boldItalic, artwork, medium})
                same(value, LunagramTypography.resolve(value), "default preserves existing face");
            same(medium, LunagramTypography.resolveAsset("fonts/rmedium.ttf", medium), "default preserves medium asset");
            same(mediumItalic, LunagramTypography.resolveAsset("fonts/rmediumitalic.ttf", mediumItalic), "default preserves italic asset");
            same(extraBold, LunagramTypography.resolveAsset("fonts/rextrabold.ttf", extraBold), "default preserves extra bold asset");
        } else {
            String family = FAMILY_NAMES[startupFamily];
            int weight = startupWeight == 2 ? 700 : startupWeight == 1 ? 500 : 400;
            face(LunagramTypography.resolve(null), family, weight, false, "startup family and weight applied to null face");
            face(LunagramTypography.resolve(normal), family, weight, false, "startup family and weight applied to UI face");
            face(LunagramTypography.resolve(italic), family, weight, true, "explicit italic retained");
            face(LunagramTypography.resolve(boldItalic), family, 700, true, "explicit bold italic retained");
            face(LunagramTypography.resolveAsset("fonts/rmedium.ttf", medium), family, 700, false,
                    "medium asset keeps semantic emphasis despite NORMAL asset style");
            face(LunagramTypography.resolveAsset("fonts/rmediumitalic.ttf", mediumItalic), family, 700, true,
                    "medium italic asset keeps both roles");
            face(LunagramTypography.resolveAsset("fonts/rextrabold.ttf", extraBold), family, 800, false,
                    "extra bold asset is not reduced to selected weight");
            face(LunagramTypography.resolveAsset("fonts/ritalic.ttf", Typeface.create("asset-ritalic", Typeface.ITALIC)),
                    family, weight, true, "italic asset preserves italic role");
            face(LunagramTypography.resolveAsset("fonts/rcondensedbold.ttf", Typeface.create("asset-condensed", Typeface.NORMAL)),
                    family, 700, false, "condensed asset preserves semantic emphasis");
        }
        same(artwork, LunagramTypography.resolve(artwork), "custom artwork face remains untouched");
        same(artwork, LunagramTypography.resolveAsset("fonts/editor-custom.ttf", artwork),
                "unknown asset remains untouched");
        for (int style = 0; style <= 3; style++) {
            Typeface code = Typeface.create(Typeface.MONOSPACE, style);
            same(code, LunagramTypography.resolve(code), "code style remains exact " + style);
            same(code, LunagramTypography.resolveAsset("fonts/rmono.ttf", code), "code asset remains exact " + style);
        }
        Typeface cached = LunagramTypography.resolve(normal);
        int weightedCreates = Typeface.weightedCreateCalls;
        same(cached, LunagramTypography.resolve(normal), "repeat resolution uses cached face");
        check(Typeface.weightedCreateCalls == weightedCreates, "cached face avoids weighted Typeface recreation");
        same(null, LunagramTypography.resolveAsset("fonts/rmedium.ttf", null), "missing asset face stays null");
        for (Typeface source : new Typeface[]{normal, artwork, Typeface.MONOSPACE}) {
            Typeface resolved = LunagramTypography.resolve(source);
            int styledCreates = Typeface.styledCreateCalls;
            same(resolved, LunagramTypography.resolve(source), "mapped and untouched faces both retain cached identity");
            check(Typeface.styledCreateCalls == styledCreates, "cached faces bypass repeated role classification");
        }
        Typeface systemMedium = Typeface.create(Typeface.DEFAULT, 500, false);
        same(systemMedium, LunagramTypography.resolve(systemMedium), "unregistered system medium stays native");
        Typeface mappedMedium = LunagramTypography.resolveSystemMedium(systemMedium);
        if (startupDefault()) same(systemMedium, mappedMedium, "default system medium preserves exact native 500 face");
        else face(mappedMedium, FAMILY_NAMES[startupFamily], startupWeight == 2 ? 700 : 500, false,
                "system medium preserves 500 role after prior untouched cache");
        int mediumCreates = Typeface.weightedCreateCalls;
        same(mappedMedium, LunagramTypography.resolveSystemMedium(systemMedium), "registered system medium uses stable cache");
        check(Typeface.weightedCreateCalls == mediumCreates, "repeated system medium registration avoids recreation");
        same(LunagramTypography.resolve(null), LunagramTypography.resolveSystemMedium(null), "null system medium remains normal resolver input");
    }

    private static void settingsPreviewAndStartupSnapshot() {
        TextPaint normal = LunagramTypography.textPaint(Paint.ANTI_ALIAS_FLAG);
        check(normal.getFlags() == Paint.ANTI_ALIAS_FLAG, "text paint preserves construction flags");
        Typeface startup = normal.getTypeface();
        Typeface startupItalic = LunagramTypography.resolve(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        for (int family = 0; family <= 4; family++) {
            for (int weight = 0; weight <= 2; weight++) {
                LunagramTypography.set(family, weight);
                check(LunagramTypography.family() == family && LunagramTypography.weight() == weight,
                        "settings pair changes immediately");
                check(LunagramTypography.requiresRestart() == (family != startupFamily || weight != startupWeight),
                        "restart notice tracks selected versus startup pair");
                int expectedWeight = weight == 2 ? 700 : weight == 1 ? 500 : 400;
                face(LunagramTypography.preview(false), FAMILY_NAMES[family], expectedWeight, false,
                        "body preview follows selected pair");
                face(LunagramTypography.preview(true), FAMILY_NAMES[family], 700, false,
                        "heading preview keeps emphasis");
                same(startup, LunagramTypography.resolve(null), "selection preserves active resolver snapshot");
                same(startup, LunagramTypography.textPaint(0).getTypeface(), "new paints use same startup snapshot");
                same(startup, normal.getTypeface(), "existing paint remains stable until restart");
                same(startupItalic, LunagramTypography.resolve(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)),
                        "selection does not replace active italic face");
                for (int style = 0; style <= 3; style++) {
                    Typeface code = Typeface.create(Typeface.MONOSPACE, style);
                    same(code, LunagramTypography.resolve(code), "selection never remaps real code face");
                }
            }
        }
        LunagramTypography.reset();
        same(Typeface.DEFAULT, LunagramTypography.preview(false), "reset restores default preview");
        same(Typeface.DEFAULT_BOLD, LunagramTypography.preview(true), "reset restores default heading preview");
        same(startup, LunagramTypography.resolve(null), "reset waits for restart to replace active face");
        check(LunagramTypography.requiresRestart() == !startupDefault(), "reset restart notice retains startup distinction");
    }

    public static void main(String[] args) {
        Build.VERSION.SDK_INT = Integer.parseInt(args[0]);
        String scenario = args[3];
        initialState(scenario, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        rolePreservation();
        validationAndPersistence();
        settingsPreviewAndStartupSnapshot();
        System.out.println("PASS " + checks + " typography behavior assertions; API "
                + Build.VERSION.SDK_INT + "; family " + startupFamily + "; weight " + startupWeight
                + "; " + scenario + " (modeled Android faces/preferences, no device rendering)");
    }
}
