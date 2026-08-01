package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads Telegram Desktop's export stylesheet and adds mobile account-viewer controls. */
final class LumaExportHtmlTheme {

    private static volatile String cachedCss;

    private static final String FALLBACK =
            "body{margin:0;font:12px/18px Arial,sans-serif}.page_header{position:fixed;top:0;width:100%;background:#fff;border-bottom:1px solid #e3e6e8}.page_header .content,.page_body{width:480px;max-width:100%;margin:auto}.page_header .text{padding:22px 24px;font-size:22px}.page_body{padding-top:64px}.entry,.default{padding:10px 16px}.details{color:#70777b}";

    private static final String EXTENSIONS =
            ".page_wrap{min-height:100vh}.page_header .content,.page_body{max-width:100%}" +
            ".list_page .entry{min-height:68px}.list_page .entry .body{min-width:0}" +
            ".list_page .entry .userpic{width:48px;height:48px}.list_page .entry .userpic .initials{line-height:48px}" +
            ".default .userpic{width:42px;height:42px}.default .userpic .initials{line-height:42px}" +
            ".default .photo{max-width:100%;height:auto}.default .media .fill{float:left;width:48px;height:48px;color:#fff;text-align:center;font-size:22px;line-height:48px}" +
            ".default .media .body{display:block;margin-left:60px}.default .media .title,.default .media .status{display:block}" +
            ".export_search{display:block;width:calc(100% - 32px);margin:16px;padding:10px 12px;border:1px solid #dfe3e6;border-radius:4px;background:#f5f7f8;color:#212121;font:inherit;outline:none}" +
            ".export_search:focus{border-color:#168acd;background:#fff}.empty{padding:32px 24px;color:#70777b;text-align:center}" +
            ".account_view{display:none;position:fixed;z-index:20;inset:0;width:100%;height:100%;border:0;background:#fff}.account_view.visible{display:block}" +
            ".account_back{display:none;position:fixed;z-index:30;top:14px;left:max(12px,calc(50% - 240px + 14px));width:36px;height:36px;border:0;border-radius:50%;background:transparent;color:#168acd;font-size:30px;line-height:32px;cursor:pointer}.account_back.visible{display:block}" +
            "@media(max-width:520px){.page_header .content .text{padding-left:18px;padding-right:18px}.list_page .entry{padding-left:14px;padding-right:14px}.account_back{left:10px}}" +
            "@media(prefers-color-scheme:dark){.account_view{background:#1a2026}.export_search{background:#2c333d;border-color:#323a45;color:#fff}.export_search:focus{border-color:#4db8ff;background:#2c333d}.empty,.default .media .status{color:#91979e}.account_back{color:#4db8ff}}";

    private LumaExportHtmlTheme() {
    }

    static String css() {
        String result = cachedCss;
        if (result != null) return result;
        try (InputStream input = ApplicationLoader.applicationContext.getAssets()
                .open("luma_export/style.css");
             ByteArrayOutputStream output = new ByteArrayOutputStream(40 * 1024)) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            result = new String(output.toByteArray(), StandardCharsets.UTF_8) + EXTENSIONS;
        } catch (Throwable error) {
            FileLog.e(error);
            result = FALLBACK + EXTENSIONS;
        }
        cachedCss = result;
        return result;
    }
}
