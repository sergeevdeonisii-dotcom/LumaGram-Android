package org.telegram.messenger;
public class DialogObject {
 public static boolean isEncryptedDialog(long id) {
  return (id & 0x4000000000000000L) != 0 && (id & 0x8000000000000000L) == 0;
 }
 public static long makeEncryptedDialogId(long id) { return 0x4000000000000000L | (id & 0xffffffffL); }
}
