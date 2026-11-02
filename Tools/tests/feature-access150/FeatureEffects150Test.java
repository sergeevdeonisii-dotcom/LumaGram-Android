package org.telegram.messenger;

import java.util.Map;
import org.telegram.messenger.LunagramFeatureCatalog.Tier;

public final class FeatureEffects150Test {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    public static void main(String[] args) {
        UserConfig.selectedAccount = 0; UserConfig.ids[0] = 15001; UserConfig.ids[1] = 15002;
        LunagramSubscriptionAccess.reset();
        LiteMode.setLiquidGlassOpacityLevel(2); LiteMode.setLiquidGlassIntensityLevel(2);
        LiteMode.setLiquidGlassInputSizeLevel(2); LiteMode.setLiquidGlassAdaptiveColorEnabled(false);
        LiteMode.setLiquidGlassWallpaperRefractionEnabled(true); LiteMode.setLiquidGlassSeparateColors(false);
        LiteMode.setLiquidGlassColorStrengthLevel(2); LiteMode.setLiquidGlassColorTransitionLevel(2);
        LumaEmergencyMode.selectDialog(0, 55);
        Map<String, ?> global = MessagesController.getGlobalMainSettings().getAll();
        Map<String, ?> own = LumaAccountData.preferences(0).getAll();
        LunagramSubscriptionAccess.setTier(0, Tier.FREE);
        check(LiteMode.getLiquidGlassEnabled() && LiteMode.getLiquidGlassKeepInPowerSaver(), "baseline glass remains on");
        check(LiteMode.getLiquidGlassOpacityLevel() == 1 && LiteMode.getLiquidGlassIntensityLevel() == 1, "default baseline panel appearance");
        check(LiteMode.getLiquidGlassInputHeightDp() == 40 && LiteMode.getLiquidGlassInputSizeLevel() == 0, "input size returns baseline");
        check(LiteMode.getLiquidGlassAdaptiveColorEnabled() && !LiteMode.getLiquidGlassWallpaperRefractionEnabled(), "default glass modes");
        check(LiteMode.getLiquidGlassSeparateColors() && LiteMode.getLiquidGlassColorStrengthLevel() == 1 && LiteMode.getLiquidGlassColorTransitionLevel() == 1, "advanced colors revert");
        check(!LumaEmergencyMode.isEnabled(0) && !LumaEmergencyMode.isSelectedDialog(0,55), "emergency effect stops");
        LiteMode.setLiquidGlassOpacityLevel(0); LiteMode.setLiquidGlassIntensityLevel(0); LiteMode.setLiquidGlassInputSizeLevel(0);
        LiteMode.setLiquidGlassAdaptiveColorEnabled(true); LiteMode.setLiquidGlassWallpaperRefractionEnabled(false);
        LiteMode.setLiquidGlassColorStrengthLevel(0); LiteMode.setLiquidGlassSeparateColors(true); LiteMode.setLiquidGlassColorTransitionLevel(0);
        LiteMode.resetLiquidGlassSettings(); LumaEmergencyMode.selectDialog(0, 66); LumaEmergencyMode.setEnabled(0, true);
        check(global.equals(MessagesController.getGlobalMainSettings().getAll()), "expired glass getters/setters never touch stored preference");
        check(own.equals(LumaAccountData.preferences(0).getAll()), "emergency state saved unchanged");
        UserConfig.selectedAccount = 1;
        check(LiteMode.getLiquidGlassOpacityLevel() == 2 && LiteMode.getLiquidGlassWallpaperRefractionEnabled(), "same global prefs use selected account authority");
        UserConfig.selectedAccount = 0; LunagramSubscriptionAccess.setTier(0, Tier.BASED);
        check(LiteMode.getLiquidGlassOpacityLevel() == 2 && LiteMode.getLiquidGlassInputHeightDp() == 44, "based renews advanced visual values");
        check(LumaEmergencyMode.isEnabled(0), "recognized former Based now includes Ultra emergency contents");
        LunagramSubscriptionAccess.setTier(0, Tier.ULTRA);
        check(LumaEmergencyMode.isEnabled(0) && LumaEmergencyMode.getDialogId(0) == 55, "ultra renews saved emergency choice");
        LunagramSubscriptionAccess.setTier(0, Tier.FREE); LumaEmergencyMode.setEnabled(0, false);
        check(!LumaAccountData.preferences(0).getBoolean("luma_emergency_mode_enabled_", true), "explicit emergency off remains allowed");
        System.out.println("PASS: " + checks + " actual-extracted glass and real emergency runtime checks.");
    }
}

final class DownloadController {
    static DownloadController getInstance(int account) { return new DownloadController(); }
    void checkAutodownloadSettings() {}
}

final class MathUtils {
    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
