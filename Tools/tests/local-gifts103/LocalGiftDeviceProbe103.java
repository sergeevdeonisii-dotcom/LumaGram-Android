package org.lunagram.tests;

import android.graphics.Path;
import dalvik.system.DexClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Isolated APK smoke test. Synthetic graph only; no accounts, keys, camera or network. */
public final class LocalGiftDeviceProbe103 {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("apk, scratch and expected outcome required");
        ClassLoader loader = new DexClassLoader(args[0], args[1], null, ClassLoader.getSystemClassLoader());
        Class<?> giftType = loader.loadClass("org.telegram.tgnet.tl.TL_stars$TL_starGiftUnique");
        Class<?> documentType = loader.loadClass("org.telegram.tgnet.TLRPC$TL_document");
        Class<?> thumbType = loader.loadClass("org.telegram.tgnet.TLRPC$TL_photoPathSize");
        Class<?> modelType = loader.loadClass("org.telegram.tgnet.tl.TL_stars$starGiftAttributeModel");
        Object gift = giftType.getConstructor().newInstance();
        Object document = documentType.getConstructor().newInstance();
        Object thumb = thumbType.getConstructor().newInstance();
        Object model = modelType.getConstructor().newInstance();
        byte[] bytes = new byte[] { 1, 2, 3 };
        Path path = new Path(); path.moveTo(0, 0); path.lineTo(1, 1);
        thumbType.getField("type").set(thumb, "j");
        thumbType.getField("bytes").set(thumb, bytes);
        thumbType.getField("svgPath").set(thumb, path);
        documentType.getField("flags").setInt(document, 1);
        documentType.getField("file_reference").set(document, new byte[0]);
        documentType.getField("mime_type").set(document, "application/x-tgsticker");
        ((ArrayList<Object>) documentType.getField("thumbs").get(document)).add(thumb);
        modelType.getField("document").set(model, document);
        ((ArrayList<Object>) giftType.getField("attributes").get(gift)).add(model);
        giftType.getField("id").setLong(gift, 1);
        giftType.getField("title").set(gift, "Synthetic local test");
        giftType.getField("slug").set(gift, "SyntheticLocalTest-1");

        Class<?> helper = loader.loadClass("org.telegram.messenger.LumaLocalGifts");
        Method clone = helper.getDeclaredMethod("cloneGraph", Object.class, IdentityHashMap.class, int[].class, int.class);
        clone.setAccessible(true);
        try {
            Object copy = clone.invoke(null, gift, new IdentityHashMap<Object,Object>(), new int[] { 0 }, 0);
            if (!"fixed".equals(args[2])) throw new AssertionError("Old APK unexpectedly accepts graph");
            Object copiedModel = ((ArrayList<?>) giftType.getField("attributes").get(copy)).get(0);
            Object copiedDocument = modelType.getField("document").get(copiedModel);
            Object copiedThumb = ((ArrayList<?>) documentType.getField("thumbs").get(copiedDocument)).get(0);
            if (copy == gift || copiedThumb == thumb || thumbType.getField("bytes").get(copiedThumb) == bytes)
                throw new AssertionError("Mutable wire graph shared");
            if (thumbType.getField("svgPath").get(copiedThumb) != null)
                throw new AssertionError("Derived path copied");
            if (thumbType.getField("svgPath").get(thumb) != path || thumbType.getField("bytes").get(thumb) != bytes)
                throw new AssertionError("Source changed");
            System.out.println("PASS: real APK accepts SVG thumbnail, detaches wire bytes and leaves source unchanged");
        } catch (InvocationTargetException failure) {
            if (!"broken".equals(args[2]) || !(failure.getCause() instanceof IllegalArgumentException)
                    || !"Unsupported mutable TL field".equals(failure.getCause().getMessage())) throw failure;
            if (thumbType.getField("svgPath").get(thumb) != path) throw new AssertionError("Source changed");
            System.out.println("PASS: real 1.0.2 APK reproduces synthetic SVG thumbnail clone rejection (IllegalArgumentException)");
        }
    }
}
