package org.telegram.messenger;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Portable preferences only. Never exports auth keys, IDs, histories, notes or the vault. */
public final class BlackHoleSettings {
    public static final int MAX_FILE_BYTES = 64 * 1024;
    public static final int PROFILE_NORMAL = 0, PROFILE_GHOST = 1, PROFILE_WORK = 2;
    private static final String PROFILE_KEY = "bhg_profile_";

    private BlackHoleSettings() {}

    public static Map<String, Object> capture(int account) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("typing.enabled", LumaTextAnimation.isEnabled());
        v.put("typing.speed", LumaTextAnimation.getSpeedLevel());
        v.put("typing.blur", LumaTextAnimation.getBlurLevel());
        v.put("typing.height", LumaTextAnimation.getHeightLevel());
        v.put("typing.swipe", LumaTextAnimation.getSwipeMode());
        v.put("format.style", LumaMessageFormatting.getAutomaticStyle());
        v.put("send.delayed", LumaDelayedSend.isEnabled());
        v.put("send.step", LumaDelayedSend.getDelayStep());
        v.put("glass.enabled", LiteMode.getLiquidGlassEnabled());
        v.put("glass.powerSaver", LiteMode.getLiquidGlassKeepInPowerSaver());
        v.put("glass.opacity", LiteMode.getLiquidGlassOpacityLevel());
        v.put("glass.intensity", LiteMode.getLiquidGlassIntensityLevel());
        v.put("glass.inputSize", LiteMode.getLiquidGlassInputSizeLevel());
        v.put("glass.adaptive", LiteMode.getLiquidGlassAdaptiveColorEnabled());
        v.put("glass.wallpaper", LiteMode.getLiquidGlassWallpaperRefractionEnabled());
        v.put("glass.separate", LiteMode.getLiquidGlassSeparateColors());
        v.put("glass.strength", LiteMode.getLiquidGlassColorStrengthLevel());
        v.put("glass.transition", LiteMode.getLiquidGlassColorTransitionLevel());
        v.put("ghost.enabled", LumaGhostMode.isEnabled(account));
        v.put("ghost.schedule", LumaGhostMode.isScheduledSendEnabled(account));
        v.put("deleted.keep", LumaDeletedMessages.isEnabled(account));
        v.put("rating.enabled", LumaStarRating.isEnabled(account));
        v.put("rating.level", LumaStarRating.getLevel(account));
        v.put("number.enabled", LumaAnonymousNumber.isEnabled(account));
        v.put("number.digits", LumaAnonymousNumber.getDigits(account));
        v.put("verification.enabled", LumaProfileVerification.isEnabled(account));
        return v;
    }

    public static void validate(Map<String, Object> values) {
        if (values.isEmpty() || values.size() > 26) throw new IllegalArgumentException("settings count");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            switch (k) {
                case "typing.enabled": case "send.delayed": case "glass.enabled":
                case "glass.powerSaver": case "glass.adaptive": case "glass.wallpaper":
                case "glass.separate": case "ghost.enabled": case "ghost.schedule":
                case "deleted.keep": case "rating.enabled": case "number.enabled":
                case "verification.enabled":
                    if (!(v instanceof Boolean)) throw new IllegalArgumentException(k);
                    break;
                case "typing.speed": case "typing.blur": case "typing.height": case "typing.swipe":
                case "glass.opacity": case "glass.intensity": case "glass.inputSize":
                case "glass.strength": case "glass.transition": checkInt(k, v, 0, 2); break;
                case "format.style": checkInt(k, v, 0, 6); break;
                case "send.step": checkInt(k, v, LumaDelayedSend.MIN_STEP, LumaDelayedSend.MAX_STEP); break;
                case "rating.level": checkInt(k, v, LumaStarRating.MIN_LEVEL, LumaStarRating.MAX_LEVEL); break;
                case "number.digits":
                    if (!(v instanceof String) || !((String) v).matches("[0-9]{8}")) throw new IllegalArgumentException(k);
                    break;
                default: throw new IllegalArgumentException("unknown setting " + k);
            }
        }
        if (Boolean.TRUE.equals(values.get("glass.adaptive")) && Boolean.TRUE.equals(values.get("glass.wallpaper")))
            throw new IllegalArgumentException("conflicting glass modes");
    }

    private static void checkInt(String k, Object v, int min, int max) {
        if (!(v instanceof Integer) && !(v instanceof Long)) throw new IllegalArgumentException(k);
        long n = ((Number) v).longValue();
        if (n < min || n > max) throw new IllegalArgumentException(k);
    }

    public static void apply(int account, Map<String, Object> changes) {
        // Validate the entire merged result before the first write: malformed imports are atomic no-ops.
        validate(changes);
        Map<String, Object> v = capture(account); v.putAll(changes); validate(v);
        LumaTextAnimation.setEnabled(b(v, "typing.enabled"));
        LumaTextAnimation.setSpeedLevel(i(v, "typing.speed"));
        LumaTextAnimation.setBlurLevel(i(v, "typing.blur"));
        LumaTextAnimation.setHeightLevel(i(v, "typing.height"));
        LumaTextAnimation.setSwipeMode(i(v, "typing.swipe"));
        LumaMessageFormatting.setAutomaticStyle(i(v, "format.style"));
        LumaDelayedSend.setEnabled(b(v, "send.delayed")); LumaDelayedSend.setDelayStep(i(v, "send.step"));
        LiteMode.toggleFlag(LiteMode.FLAG_LIQUID_GLASS, b(v, "glass.enabled"));
        LiteMode.setLiquidGlassKeepInPowerSaver(b(v, "glass.powerSaver"));
        LiteMode.setLiquidGlassOpacityLevel(i(v, "glass.opacity"));
        LiteMode.setLiquidGlassIntensityLevel(i(v, "glass.intensity"));
        LiteMode.setLiquidGlassInputSizeLevel(i(v, "glass.inputSize"));
        LiteMode.setLiquidGlassAdaptiveColorEnabled(false); LiteMode.setLiquidGlassWallpaperRefractionEnabled(false);
        LiteMode.setLiquidGlassAdaptiveColorEnabled(b(v, "glass.adaptive"));
        LiteMode.setLiquidGlassWallpaperRefractionEnabled(b(v, "glass.wallpaper"));
        LiteMode.setLiquidGlassSeparateColors(b(v, "glass.separate"));
        LiteMode.setLiquidGlassColorStrengthLevel(i(v, "glass.strength"));
        LiteMode.setLiquidGlassColorTransitionLevel(i(v, "glass.transition"));
        LumaGhostMode.setScheduledSendEnabled(account, b(v, "ghost.schedule"));
        LumaGhostMode.setEnabled(account, b(v, "ghost.enabled"));
        LumaDeletedMessages.setEnabled(account, b(v, "deleted.keep"));
        LumaStarRating.setLevel(account, i(v, "rating.level")); LumaStarRating.setEnabled(account, b(v, "rating.enabled"));
        LumaAnonymousNumber.setDigits(account, (String) v.get("number.digits"));
        LumaAnonymousNumber.setEnabled(account, b(v, "number.enabled"));
        LumaProfileVerification.setEnabled(account, b(v, "verification.enabled"));
    }

    private static boolean b(Map<String, Object> v, String k) { return (Boolean) v.get(k); }
    private static int i(Map<String, Object> v, String k) { return ((Number) v.get(k)).intValue(); }

    public static String encode(Map<String, Object> values) throws Exception {
        validate(values);
        return new JSONObject().put("format", "blackholegram-settings").put("schema", 1)
                .put("settings", new JSONObject(values)).toString(2);
    }

    public static Map<String, Object> decode(String text) throws Exception {
        if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
            throw new IllegalArgumentException("file too large");
        JSONObject root = new JSONObject(text);
        if (!"blackholegram-settings".equals(root.getString("format")) || root.getInt("schema") != 1)
            throw new IllegalArgumentException("unsupported format");
        JSONObject data = root.getJSONObject("settings");
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) { String key = keys.next(); values.put(key, data.get(key)); }
        validate(values); return values;
    }

    public static Map<String, Object> profile(int account, int id) throws Exception {
        checkProfile(id);
        String saved = LumaAccountData.preferences(account).getString(PROFILE_KEY + id, null);
        if (saved != null) return decode(saved);
        Map<String, Object> values = capture(account);
        values.put("ghost.enabled", id == PROFILE_GHOST);
        values.put("ghost.schedule", id == PROFILE_GHOST);
        if (id == PROFILE_WORK) {
            values.put("typing.enabled", false); values.put("glass.enabled", false);
            values.put("format.style", 0); values.put("send.delayed", true); values.put("send.step", 10);
        }
        LumaAccountData.preferences(account).edit().putString(PROFILE_KEY + id, encode(values)).apply();
        return values;
    }

    public static void saveProfile(int account, int id) throws Exception {
        checkProfile(id);
        LumaAccountData.preferences(account).edit().putString(PROFILE_KEY + id, encode(capture(account))).apply();
    }

    private static void checkProfile(int id) {
        if (id < PROFILE_NORMAL || id > PROFILE_WORK) throw new IllegalArgumentException("profile");
    }
}
