package org.telegram.messenger;
/** Minimal preference adapter; Android rendering and LiteMode caching are not model tests. */
public final class LiteMode {
 public static final int FLAG_LIQUID_GLASS=1;
 private static final java.util.Map<String,Object> v=new java.util.HashMap<>();
 private static boolean b(String k){return (Boolean)v.getOrDefault(k,true);}
 private static int i(String k){return (Integer)v.getOrDefault(k,1);}
 public static boolean getLiquidGlassEnabled(){return b("enabled");}
 public static void toggleFlag(int flag,boolean enabled){v.put("enabled",enabled);}
 public static boolean getLiquidGlassKeepInPowerSaver(){return b("power");}
 public static void setLiquidGlassKeepInPowerSaver(boolean x){v.put("power",x);}
 public static int getLiquidGlassOpacityLevel(){return i("opacity");}
 public static void setLiquidGlassOpacityLevel(int x){v.put("opacity",x);}
 public static int getLiquidGlassIntensityLevel(){return i("intensity");}
 public static void setLiquidGlassIntensityLevel(int x){v.put("intensity",x);}
 public static int getLiquidGlassInputSizeLevel(){return i("size");}
 public static void setLiquidGlassInputSizeLevel(int x){v.put("size",x);}
 public static boolean getLiquidGlassAdaptiveColorEnabled(){return (Boolean)v.getOrDefault("adaptive",false);}
 public static void setLiquidGlassAdaptiveColorEnabled(boolean x){v.put("adaptive",x);}
 public static boolean getLiquidGlassWallpaperRefractionEnabled(){return (Boolean)v.getOrDefault("wallpaper",false);}
 public static void setLiquidGlassWallpaperRefractionEnabled(boolean x){v.put("wallpaper",x);}
 public static boolean getLiquidGlassSeparateColors(){return b("separate");}
 public static void setLiquidGlassSeparateColors(boolean x){v.put("separate",x);}
 public static int getLiquidGlassColorStrengthLevel(){return i("strength");}
 public static void setLiquidGlassColorStrengthLevel(int x){v.put("strength",x);}
 public static int getLiquidGlassColorTransitionLevel(){return i("transition");}
 public static void setLiquidGlassColorTransitionLevel(int x){v.put("transition",x);}
}
