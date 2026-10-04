package org.telegram.messenger;
public class MessagesController {
 public static android.content.SharedPreferences getGlobalMainSettings(){return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig",0);}
 public static final boolean[] ghostState = new boolean[4];
 private final int account; private MessagesController(int account) { this.account=account; }
 public static MessagesController getInstance(int account) { return new MessagesController(account); }
 public void setLumaGhostModeEnabled(boolean enabled) { ghostState[account]=enabled; }
}
