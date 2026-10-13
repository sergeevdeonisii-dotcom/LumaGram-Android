package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** One process-wide, off-main-thread decoded set of four cat clips. No account data. */
final class LunagramPetAtlas {
    static final int MESH_SEGMENTS = 12;
    private static final String ROOT = "lunapet/";
    private static final String[] NAMES = {"walk", "loaf", "roll", "ball"};
    private static final Object LOCK = new Object();
    private static final ArrayList<WeakReference<LunagramPetView>> WAITERS = new ArrayList<>();
    private static volatile LunagramPetAtlas cached;
    private static boolean loading, failed;
    private final Bitmap[] frames = new Bitmap[64];
    private final float[] anchorsX = new float[64], anchorsY = new float[64];
    private final float[] heights = new float[64];
    private float highestAnchor = 0, belowAnchor = 0, widestAnchor = .5f;

    static LunagramPetAtlas request(LunagramPetView view) {
        if (cached != null) return cached;
        synchronized (LOCK) {
            if (cached != null) return cached;
            if (failed) return null;
            boolean found = false;
            for (int i = WAITERS.size() - 1; i >= 0; i--) {
                LunagramPetView item = WAITERS.get(i).get();
                if (item == null) WAITERS.remove(i);
                else if (item == view) found = true;
            }
            if (!found) WAITERS.add(new WeakReference<>(view));
            if (loading) return null;
            loading = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            LunagramPetAtlas result = null;
            try { result = load(); }
            catch (Exception | OutOfMemoryError error) { FileLog.e(error); }
            final LunagramPetAtlas decoded = result;
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<WeakReference<LunagramPetView>> listeners;
                synchronized (LOCK) {
                    cached = decoded; loading = false; failed = decoded == null;
                    listeners = new ArrayList<>(WAITERS); WAITERS.clear();
                }
                for (WeakReference<LunagramPetView> ref : listeners) {
                    LunagramPetView item = ref.get();
                    if (item != null) item.onAtlasReady(decoded);
                }
            });
        });
        return null;
    }

    static void cancel(LunagramPetView view) {
        synchronized (LOCK) {
            for (int i = WAITERS.size() - 1; i >= 0; i--) {
                LunagramPetView item = WAITERS.get(i).get();
                if (item == null || item == view) WAITERS.remove(i);
            }
        }
    }

    private static LunagramPetAtlas load() throws Exception {
        JSONObject manifest;
        try (InputStream input = ApplicationLoader.applicationContext.getAssets().open(ROOT + "ginger-tabby.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (buffer.size() + count > 65536) throw new IllegalArgumentException("Oversized cat manifest");
                buffer.write(chunk, 0, count);
            }
            manifest = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        }
        if (manifest.getInt("version") != 1) throw new IllegalArgumentException("Unknown cat atlas version");
        JSONArray entries = manifest.getJSONArray("clips");
        if (entries.length() != 4) throw new IllegalArgumentException("Cat needs four clips");
        LunagramPetAtlas atlas = new LunagramPetAtlas();
        try {
            for (int entry = 0; entry < entries.length(); entry++) {
                JSONObject clip = entries.getJSONObject(entry);
                int index = -1;
                for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(clip.getString("name"))) index = i;
                if (index < 0 || atlas.frames[index * 16] != null || clip.getInt("columns") != 4 || clip.getInt("rows") != 4)
                    throw new IllegalArgumentException("Invalid cat clip layout");
                String name = "ginger-" + NAMES[index] + ".png";
                if (!name.equals(clip.getString("asset"))) throw new IllegalArgumentException("Invalid cat clip asset");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream input = ApplicationLoader.applicationContext.getAssets().open(ROOT + name)) {
                    BitmapFactory.decodeStream(input, null, options);
                }
                int originalSize = options.outWidth;
                if (originalSize < 64 || originalSize > 8192 || originalSize != options.outHeight)
                    throw new IllegalArgumentException("Invalid cat clip dimensions");
                options.inSampleSize = 1;
                while (originalSize / options.inSampleSize > 1024) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inScaled = false;
                Bitmap bitmap;
                try (InputStream input = ApplicationLoader.applicationContext.getAssets().open(ROOT + name)) {
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                }
                if (bitmap == null) throw new IllegalArgumentException("Cannot decode cat clip");
                try {
                    if (bitmap.getWidth() != bitmap.getHeight()) throw new IllegalArgumentException("Invalid decoded clip size");
                    JSONArray entriesFrames = clip.getJSONArray("frames");
                    if (entriesFrames.length() != 16) throw new IllegalArgumentException("Cat clip needs sixteen poses");
                    for (int f = 0; f < 16; f++) {
                    int frame = index * 16 + f;
                    JSONObject anchor = entriesFrames.getJSONObject(f);
                    Rect original = new Rect(originalSize * (f % 4) / 4, originalSize * (f / 4) / 4,
                            originalSize * (f % 4 + 1) / 4, originalSize * (f / 4 + 1) / 4);
                    JSONObject crop = anchor.optJSONObject("sourceRect");
                    if (crop != null) original.set(crop.getInt("left"), crop.getInt("top"), crop.getInt("right"), crop.getInt("bottom"));
                    if (original.left < 0 || original.top < 0 || original.right > originalSize || original.bottom > originalSize
                            || original.width() < 8 || original.height() < 8) throw new IllegalArgumentException("Invalid cat crop");
                    float x = (float) anchor.getDouble("anchorX") / original.width();
                    float y = (float) anchor.getDouble("anchorY") / original.width();
                    float height = original.height() / (float) original.width();
                    if (!finite(x) || !finite(y) || x < 0 || x > 1 || y <= 0 || y > height)
                        throw new IllegalArgumentException("Invalid cat floor anchor");
                    atlas.anchorsX[frame] = x; atlas.anchorsY[frame] = y; atlas.heights[frame] = height;
                    atlas.highestAnchor = Math.max(atlas.highestAnchor, y);
                    atlas.belowAnchor = Math.max(atlas.belowAnchor, height - y);
                    atlas.widestAnchor = Math.max(atlas.widestAnchor, Math.max(x, 1 - x));
                    int l = original.left * bitmap.getWidth() / originalSize, t = original.top * bitmap.getHeight() / originalSize;
                    int r = original.right * bitmap.getWidth() / originalSize, b = original.bottom * bitmap.getHeight() / originalSize;
                    atlas.frames[frame] = Bitmap.createBitmap(bitmap, l, t, r - l, b - t);
                    atlas.frames[frame].prepareToDraw();
                    }
                } finally {
                    bitmap.recycle();
                }
            }
            return atlas;
        } catch (Exception | OutOfMemoryError error) {
            for (Bitmap bitmap : atlas.frames) if (bitmap != null) bitmap.recycle();
            throw error;
        }
    }

    float fittedSize(float desired, float width, float aboveFloor, float belowFloor) {
        float size = Math.min(desired, Math.max(0, width) / (widestAnchor * 2));
        size = Math.min(size, Math.max(0, aboveFloor) / highestAnchor);
        if (belowAnchor > 0) size = Math.min(size, Math.max(0, belowFloor) / belowAnchor);
        return Math.max(0, size);
    }
    float footprint(float size) { return size * widestAnchor * 2; }
    void draw(Canvas canvas, int frame, float size, Paint paint, RectF destination, float tail, float[] mesh) {
        frame = Math.max(0, Math.min(63, frame));
        destination.set(-anchorsX[frame] * size, -anchorsY[frame] * size,
                (1 - anchorsX[frame]) * size, (heights[frame] - anchorsY[frame]) * size);
        // One silhouette per frame. Alpha crossfades would produce a two-headed cat.
        if (tail == 0 || frame < 16 || frame >= 32) {
            canvas.drawBitmap(frames[frame], null, destination, paint);
            return;
        }
        for (int row = 0; row <= MESH_SEGMENTS; row++) for (int col = 0; col <= MESH_SEGMENTS; col++) {
            float y = destination.top + destination.height() * row / MESH_SEGMENTS;
            float weight = Math.max(0, y) / Math.max(1, destination.bottom);
            int i = (row * (MESH_SEGMENTS + 1) + col) * 2;
            // The denser grid confines deformation to the dangling tail; torso
            // and planted paws above the rail do not breathe/stretch as a unit.
            mesh[i] = destination.left + destination.width() * col / MESH_SEGMENTS + tail * size * .012f * weight;
            mesh[i + 1] = y;
        }
        canvas.drawBitmapMesh(frames[frame], MESH_SEGMENTS, MESH_SEGMENTS, mesh, 0, null, 0, paint);
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
