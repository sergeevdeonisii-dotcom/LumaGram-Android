package org.telegram.messenger;
public class MessagesController {
 public static android.content.SharedPreferences getGlobalMainSettings(){return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig",0);}
}
