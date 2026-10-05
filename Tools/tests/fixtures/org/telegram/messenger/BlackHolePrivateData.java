package org.telegram.messenger;
/** In-memory adapter ONLY. Production uses Android Keystore; cryptography is tested separately. */
public final class BlackHolePrivateData {
 public static String read(int account,String name,String fallback){return LumaAccountData.preferences(account).getString(name,fallback);}
 public static void write(int account,String name,String text){LumaAccountData.preferences(account).edit().putString(name,text).apply();}
 public static void remove(int account,String name){LumaAccountData.preferences(account).edit().remove(name).apply();}
 public static boolean isAvailable(){return true;}
}
