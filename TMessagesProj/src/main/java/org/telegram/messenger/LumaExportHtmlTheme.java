package org.telegram.messenger;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Writes the unmodified visual assets used by Telegram Desktop HTML exports. */
final class LumaExportHtmlTheme {

    private static final int IMAGE_MAP_COUNT = 7;
    private static volatile String cachedCss;
    private static volatile String cachedInlineCss;
    private static volatile String cachedScript;

    private LumaExportHtmlTheme() {
    }

    static String css() {
        String result = cachedCss;
        if (result != null) return result;
        try {
            result = readTextAsset("luma_export/style.css");
        } catch (Throwable error) {
            FileLog.e(error);
            result = "body{margin:0;font:12px/18px Arial,sans-serif}.page_header .content,.page_body{width:480px;margin:auto}";
        }
        cachedCss = result;
        return result;
    }

    /**
     * CSS variant for documents opened through Android's content:// provider.
     * Chrome cannot resolve sibling files from those URLs, so the small UI
     * images used by Telegram Desktop are embedded as data URIs.
     */
    static String inlineCss() {
        String result = cachedInlineCss;
        if (result != null) return result;
        synchronized (LumaExportHtmlTheme.class) {
            result = cachedInlineCss;
            if (result != null) return result;
            try {
                result = css();
                for (int index = 0; index < IMAGE_MAP_COUNT; index++) {
                    JSONObject images = new JSONObject(readTextAsset("luma_export/images_" + index + ".json"));
                    Iterator<String> names = images.keys();
                    while (names.hasNext()) {
                        String name = names.next();
                        result = result.replace("../images/" + name,
                                "data:image/png;base64," + images.getString(name));
                    }
                }
            } catch (Throwable error) {
                FileLog.e(error);
                result = css();
            }
            cachedInlineCss = result;
            return result;
        }
    }

    static String script() {
        String result = cachedScript;
        if (result != null) return result;
        try {
            result = readTextAsset("luma_export/script.js");
        } catch (Throwable error) {
            FileLog.e(error);
            result = "function CheckLocation(){}function GoBack(){return true;}";
        }
        cachedScript = result;
        return result;
    }

    static void writeAssets(File exportRoot) throws Exception {
        File cssDir = new File(exportRoot, "css");
        File jsDir = new File(exportRoot, "js");
        File imagesDir = new File(exportRoot, "images");
        ensureDirectory(cssDir);
        ensureDirectory(jsDir);
        ensureDirectory(imagesDir);

        writeBytes(new File(cssDir, "style.css"), css().getBytes(StandardCharsets.UTF_8));
        writeBytes(new File(jsDir, "script.js"), script().getBytes(StandardCharsets.UTF_8));

        String imagesRoot = imagesDir.getCanonicalPath() + File.separator;
        for (int index = 0; index < IMAGE_MAP_COUNT; index++) {
            JSONObject images = new JSONObject(readTextAsset("luma_export/images_" + index + ".json"));
            Iterator<String> names = images.keys();
            while (names.hasNext()) {
                String name = names.next();
                File target = new File(imagesDir, name);
                if (!target.getCanonicalPath().startsWith(imagesRoot)) {
                    throw new SecurityException("Unsafe export asset name");
                }
                writeBytes(target, Base64.decode(images.getString(name), Base64.DEFAULT));
            }
        }
    }

    private static String readTextAsset(String path) throws IOException {
        try (InputStream input = ApplicationLoader.applicationContext.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream(40 * 1024)) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeBytes(File target, byte[] bytes) throws IOException {
        try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            output.write(bytes);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory.getName());
        }
    }
}
