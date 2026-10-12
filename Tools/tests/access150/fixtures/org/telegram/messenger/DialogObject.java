package org.telegram.messenger;
public final class DialogObject { public static boolean isEncryptedDialog(long dialogId) { return (dialogId&0x4000000000000000L)!=0; } }
