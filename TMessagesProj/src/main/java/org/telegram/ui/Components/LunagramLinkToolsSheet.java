package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.widget.ImageView;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramLinkTools;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramTextTools;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LunagramFeatureGate;

/** Explicit local-only link operations. No browser, network request or automatic draft changes. */
public final class LunagramLinkToolsSheet {
    private final BaseFragment host;
    private final int account;
    private final LunagramMediaAccess.Session session;
    private final BooleanSupplier contextValid;
    private final String source, citation;
    private final LinkedHashMap<String, String> links = new LinkedHashMap<>();

    private LunagramLinkToolsSheet(BaseFragment host, int account, CharSequence source,
            ArrayList<TLRPC.MessageEntity> entities, String citation, BooleanSupplier contextValid) {
        this.host = host; this.account = account; this.source = LunagramTextTools.requireInput(source);
        this.citation = citation == null ? "" : LunagramTextTools.requireInput(citation);
        this.contextValid = contextValid; session = LunagramMediaAccess.forAccount(account);
        if (entities != null) for (TLRPC.MessageEntity entity : entities) {
            if (entity.offset < 0 || entity.length < 0 || (long) entity.offset + entity.length > this.source.length()) continue;
            if (entity instanceof TLRPC.TL_messageEntityTextUrl) add(entity.url, this.source.substring(entity.offset, entity.offset + entity.length));
            else if (entity instanceof TLRPC.TL_messageEntityUrl) add(this.source.substring(entity.offset, entity.offset + entity.length), null);
        }
        Matcher matcher = AndroidUtilities.WEB_URL.matcher(this.source);
        while (matcher.find() && links.size() < 32) add(matcher.group(), null);
    }
    private void add(String target, String label) {
        if (target == null || target.length() > LunagramLinkTools.MAX_URL || links.size() >= 32) return;
        if (!target.contains(":")) target = "https://" + target;
        try { LunagramLinkTools.inspect(target); if (!links.containsKey(target)) links.put(target, label); }
        catch (IllegalArgumentException ignored) {}
    }
    public static void show(BaseFragment host, int account, CharSequence source, ArrayList<TLRPC.MessageEntity> entities,
            String citation, BooleanSupplier contextValid) {
        if (host == null || host.getParentActivity() == null || host.getCurrentAccount() != account || account != UserConfig.selectedAccount
                || contextValid == null || !contextValid.getAsBoolean() || !LunagramFeatureGate.require(host, LunagramFeatureCatalog.Feature.LINK_TOOLS)) return;
        try { new LunagramLinkToolsSheet(host, account, source, entities, citation, contextValid).open(); }
        catch (IllegalArgumentException error) { error(host, R.string.LutTooLong); }
    }
    private boolean valid() {
        return host.getParentActivity() != null && !host.getParentActivity().isFinishing() && host.getCurrentAccount() == account
                && account == UserConfig.selectedAccount && session.canUse(LunagramFeatureCatalog.Feature.LINK_TOOLS) && contextValid.getAsBoolean();
    }
    private void open() {
        if (!valid()) return;
        ArrayList<CharSequence> labels = new ArrayList<>(); ArrayList<Runnable> actions = new ArrayList<>();
        if (!links.isEmpty()) {
            labels.add(s(R.string.LulInspect)); actions.add(() -> chooseLink(false));
            labels.add(s(R.string.LulClean)); actions.add(() -> chooseLink(true));
        }
        labels.add(s(R.string.LulQr)); actions.add(() -> qr(source));
        if (!citation.isEmpty()) { labels.add(s(R.string.LulCitation)); actions.add(this::citationPreview); }
        host.showDialog(builder(R.string.LulTitle).setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            if (valid() && which >= 0 && which < actions.size()) actions.get(which).run();
        }).create());
    }
    private void chooseLink(boolean clean) {
        if (!valid()) return;
        ArrayList<String> urls = new ArrayList<>(links.keySet());
        if (urls.size() == 1) { showLink(urls.get(0), clean); return; }
        CharSequence[] labels = new CharSequence[urls.size()];
        for (int i = 0; i < urls.size(); i++) labels[i] = (i + 1) + ". " + urls.get(i);
        host.showDialog(builder(R.string.LulChooseLink).setItems(labels, (d, which) -> {
            if (valid() && which >= 0 && which < urls.size()) showLink(urls.get(which), clean);
        }).create());
    }
    private void showLink(String url, boolean clean) {
        if (!valid()) return;
        try {
            if (clean) {
                LunagramLinkTools.Cleaned result = LunagramLinkTools.clean(url);
                String status = s(result.protectedQuery ? R.string.LulProtected : result.removed == 0 ? R.string.LulNoTracking : R.string.LulCleanPreview);
                host.showDialog(builder(R.string.LulClean).setMessage(status + "\n\n" + result.url)
                        .setPositiveButton(s(R.string.Copy), (d, which) -> { if (valid()) AndroidUtilities.addToClipboard(result.url); }).create());
            } else {
                LunagramLinkTools.Inspection info = LunagramLinkTools.inspect(url);
                StringBuilder content = new StringBuilder(s(R.string.LulLocalOnly));
                content.append("\n\n").append(s(R.string.LulHost)).append(": ").append(info.asciiHost.isEmpty() ? "—" : info.asciiHost);
                content.append("\n").append(s(R.string.LulScheme)).append(": ").append(info.scheme);
                if (!info.web || info.insecure) content.append("\n\n").append(s(R.string.LulInsecure));
                if (info.credentials) content.append("\n\n").append(s(R.string.LulCredentials));
                if (info.international) content.append("\n\n").append(s(R.string.LulInternational));
                if (info.invisible) content.append("\n\n").append(s(R.string.LulInvisible));
                String visible = links.get(url);
                if (!TextUtils.isEmpty(visible) && visible.contains("://") && !LunagramLinkTools.sameHost(visible, url))
                    content.append("\n\n").append(s(R.string.LulMismatch));
                content.append("\n\n").append(url);
                host.showDialog(builder(R.string.LulInspect).setMessage(content).setPositiveButton(s(R.string.OK), null).create());
            }
        } catch (IllegalArgumentException error) { error(host, R.string.LulInvalid); }
    }
    private void citationPreview() {
        if (!valid()) return;
        String text = citation + "\n\n" + source;
        if (text.length() > LunagramTextTools.MAX_INPUT) { error(host, R.string.LutTooLong); return; }
        host.showDialog(builder(R.string.LulCitation).setMessage(text)
                .setPositiveButton(s(R.string.Copy), (d, which) -> { if (valid()) AndroidUtilities.addToClipboard(text); }).create());
    }
    private void qr(String text) {
        if (!valid()) return;
        if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 1024) { error(host, R.string.LulQrLimit); return; }
        Bitmap bitmap;
        // Render ordinary QR modules with a quiet zone; no logo covers data and no text leaves the app.
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M); hints.put(EncodeHintType.MARGIN, 4);
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints);
            int[] pixels = new int[512 * 512];
            for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) pixels[y * 512 + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
            bitmap = Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888);
        } catch (Exception ignored) { error(host, R.string.LulInvalid); return; }
        ImageView preview = new ImageView(host.getParentActivity()); preview.setImageBitmap(bitmap);
        preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setMaxHeight(AndroidUtilities.dp(280));
        preview.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        preview.setContentDescription(s(R.string.LulQr));
        host.showDialog(builder(R.string.LulQr).setMessage(s(R.string.LulQrInfo)).setView(preview)
                .setPositiveButton(s(R.string.OK), null).create());
    }
    private AlertDialog.Builder builder(int title) { return new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider()).setTitle(s(title)).setNegativeButton(s(R.string.Cancel), null); }
    private static String s(int id) { return LocaleController.getString(id); }
    private static void error(BaseFragment host, int id) { if (host.getParentActivity() != null) host.showDialog(new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider()).setMessage(s(id)).setPositiveButton(s(R.string.OK), null).create()); }
}
