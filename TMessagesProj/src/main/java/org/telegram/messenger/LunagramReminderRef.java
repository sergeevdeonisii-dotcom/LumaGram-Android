package org.telegram.messenger;

/** Persisted message reference. It deliberately contains no copied message or chat text. */
public final class LunagramReminderRef {
    public final int account, messageId;
    public final long owner, dialogId, whenMillis;
    public LunagramReminderRef(int account, long owner, long dialogId, int messageId, long whenMillis) {
        if (account < 0 || owner <= 0 || dialogId == 0 || messageId == 0 || whenMillis <= 0) throw new IllegalArgumentException("Invalid reminder reference");
        this.account = account; this.owner = owner; this.dialogId = dialogId; this.messageId = messageId; this.whenMillis = whenMillis;
    }
    public boolean belongsTo(int account, long owner) { return this.account == account && owner > 0 && this.owner == owner; }
    public String encode() { return "1;" + account + ";" + owner + ";" + dialogId + ";" + messageId + ";" + whenMillis; }
    public static LunagramReminderRef decode(String value) {
        if (value == null || value.length() > 160) return null;
        try {
            String[] fields = value.split(";", -1);
            if (fields.length != 6 || !"1".equals(fields[0])) return null;
            return new LunagramReminderRef(Integer.parseInt(fields[1]), Long.parseLong(fields[2]), Long.parseLong(fields[3]), Integer.parseInt(fields[4]), Long.parseLong(fields[5]));
        } catch (RuntimeException malformed) { return null; }
    }
}
