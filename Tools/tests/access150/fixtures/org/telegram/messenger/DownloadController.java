package org.telegram.messenger;
public final class DownloadController {
    public static final int[] changes=new int[4];
    private final int account;
    private DownloadController(int account) { this.account=account; }
    public static DownloadController getInstance(int account) { return new DownloadController(account); }
    public void checkAutodownloadSettings() { changes[account]++; }
}
