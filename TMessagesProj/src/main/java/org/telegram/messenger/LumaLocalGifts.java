package org.telegram.messenger;

import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Device-only display copies. These objects are never Telegram inventory or payment inputs. */
public final class LumaLocalGifts {
    private static final String STORE = "luma_local_unique_gifts_v1";
    private static final String HEADER = "LUMA_LOCAL_GIFTS_1\n";
    private static final int MAX_GIFTS = 200;
    private static final int MAX_GIFT_BYTES = 256 * 1024;
    private static final int MAX_STORE_CHARS = 6 * 1024 * 1024;
    private static final int MAX_ENCRYPTED_CHARS = 9 * 1024 * 1024;
    private static final Map<Integer, Cache> caches = new HashMap<>();
    // Separate from the data-helper monitor: UI availability must never wait for
    // a worker decrypting/decoding the entire local collection under that monitor.
    private static final Object availabilityLock = new Object();
    private static final Map<Integer, UiAvailability> uiAvailability = new HashMap<>();
    private static final Map<Integer, UiRequest> uiRequests = new HashMap<>();
    private static final long[] availabilityEpochs = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private LumaLocalGifts() { }

    /** Marker for local UI. Even accidentally serializing the wrapper fails closed. */
    public static final class LocalSavedGift extends TL_stars.TL_savedStarGift {
        public final String localKey;
        public final long ownerUserId;

        private LocalSavedGift(long owner, TL_stars.TL_starGiftUnique unique) {
            localKey = key(unique);
            ownerUserId = owner;
            gift = unique;
            gift_num = unique.num;
            saved_id = -unique.id;
            // No invented transaction, sender, date, transfer allowance or resale state.
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            throw new UnsupportedOperationException("Local gift is not a Telegram transaction");
        }
    }

    public static synchronized boolean add(int account, TL_stars.TL_starGiftUnique source) {
        return add(account, owner(account), source);
    }

    /** Stamp asynchronous UI work with the identity that initiated it, not a reusable slot. */
    public static synchronized boolean add(int account, long expectedOwner, TL_stars.TL_starGiftUnique source) {
        final long owner = expectedOwner;
        if (owner <= 0 || owner(account) != owner || !valid(source)
                || !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_NFT)) return false;
        String phase = "storage-read";
        try {
            ArrayList<Record> records = new ArrayList<>(readRecords(account, owner));
            String wanted = key(source);
            for (Record record : records) if (record.key.equals(wanted)) return false;
            if (records.size() >= MAX_GIFTS) return false;

            // Telegram serializers update flags and nested attribute fields. Clone the complete
            // public TL graph first, so serializing cannot change a real market/server object.
            phase = "clone";
            TL_stars.TL_starGiftUnique unique = (TL_stars.TL_starGiftUnique)
                    cloneGraph(source, new IdentityHashMap<>(), new int[] { 0 }, 0);
            adaptOwner(unique, owner);
            phase = "serialize";
            byte[] bytes = serialize(unique);
            phase = "decode";
            decode(bytes, owner); // Validate the exact persisted bytes with the real TL decoder.
            records.add(0, new Record(wanted, bytes));
            phase = "storage-write";
            if (!LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_NFT)) return false;
            writeRecords(account, owner, records);
            return true;
        } catch (Exception error) {
            // Fail closed on Keystore failure, corrupt storage, oversized data or account reuse.
            // Never log the exception message/stack: either may include decrypted data or IDs.
            Log.w("LumaLocalGifts", "Local add failed phase=" + phase + " class=" + error.getClass().getSimpleName());
            return false;
        }
    }

    /** Fresh detached instances on every call; changing a tile never changes saved bytes. */
    public static synchronized List<LocalSavedGift> entries(int account) {
        final long owner = owner(account);
        ArrayList<LocalSavedGift> result = new ArrayList<>();
        if (owner <= 0) return result;
        try {
            for (Record record : readRecords(account, owner)) {
                result.add(new LocalSavedGift(owner, decode(record.bytes, owner)));
            }
            requireOwner(account, owner);
            return result;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** Paid profile overlay only. Raw entries remain readable/removable after expiry. */
    public static List<LocalSavedGift> entriesForUi(int account) {
        return LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_NFT)
                ? entries(account) : Collections.emptyList();
    }

    public static synchronized boolean contains(int account, TL_stars.TL_starGiftUnique unique) {
        final long owner = owner(account);
        if (owner <= 0 || !valid(unique)) return false;
        try {
            String wanted = key(unique);
            for (Record record : readRecords(account, owner)) {
                if (record.key.equals(wanted)) {
                    requireOwner(account, owner);
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    public static synchronized int count(int account) {
        final long owner = owner(account);
        if (owner <= 0) return 0;
        try {
            int count = readRecords(account, owner).size();
            requireOwner(account, owner);
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static boolean hasAny(int account) { return count(account) != 0; }

    /**
     * Cheap, fail-closed tab visibility. Cold/changed data returns false while a
     * single cancelable worker validates it; an owner-stamped event refreshes UI.
     * No unencrypted count hint is trusted and no TL/Keystore work runs here.
     */
    public static boolean hasAnyForUi(int account) {
        final long owner = owner(account);
        if (owner <= 0 || !LunagramSubscriptionAccess.canUse(account, LunagramFeatureCatalog.Feature.LOCAL_NFT)) {
            invalidateAvailability(account);
            return false;
        }
        final String snapshot;
        try {
            snapshot = encryptedSnapshot(account, owner);
        } catch (Exception ignored) {
            invalidateAvailability(account);
            return false;
        }
        synchronized (availabilityLock) {
            if (owner(account) != owner) return false;
            UiAvailability cached = uiAvailability.get(account);
            if (cached != null && cached.owner == owner && sameSnapshot(snapshot, cached.snapshot)) return cached.hasAny;
            UiRequest pending = uiRequests.get(account);
            if (pending != null && pending.owner == owner && sameSnapshot(snapshot, pending.snapshot)) return false;
            invalidateAvailabilityLocked(account);
            final UiRequest request = new UiRequest(owner, snapshot, availabilityEpochs[account]);
            request.worker = () -> validateAvailability(account, request);
            uiRequests.put(account, request);
            if (!Utilities.globalQueue.postRunnable(request.worker)) uiRequests.remove(account);
            return false;
        }
    }

    public static synchronized boolean remove(int account, LocalSavedGift gift) {
        final long owner = owner(account);
        if (gift == null || owner <= 0 || gift.ownerUserId != owner) return false;
        try {
            ArrayList<Record> records = new ArrayList<>(readRecords(account, owner));
            boolean removed = records.removeIf(record -> record.key.equals(gift.localKey));
            if (removed) writeRecords(account, owner, records);
            return removed;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Optional memory cleanup; persisted data is already removed by LumaAccountData on logout. */
    public static synchronized void clearCache(int account) {
        caches.remove(account);
        invalidateAvailability(account);
    }

    private static String encryptedSnapshot(int account, long owner) {
        requireOwner(account, owner);
        SharedPreferences preferences = LumaAccountData.preferences(account);
        requireOwner(account, owner);
        String snapshot = preferences.getString(STORE, null);
        requireOwner(account, owner);
        return snapshot;
    }

    private static boolean sameSnapshot(String first, String second) {
        return first == second || (first != null && first.equals(second));
    }

    private static void validateAvailability(int account, UiRequest request) {
        // hasAny is the authoritative bounded/decrypting path, confined to the worker.
        boolean validated = owner(account) == request.owner && hasAny(account);
        AndroidUtilities.runOnUIThread(() -> {
            final String current;
            try {
                current = encryptedSnapshot(account, request.owner);
            } catch (Exception ignored) {
                discardAvailabilityRequest(account, request);
                return;
            }
            synchronized (availabilityLock) {
                if (uiRequests.get(account) != request || availabilityEpochs[account] != request.epoch) return;
                uiRequests.remove(account);
                if (owner(account) != request.owner || !sameSnapshot(current, request.snapshot)) return;
                // Cache false too (empty/corrupt/unavailable data) so the event cannot
                // trigger an endless validate -> refresh -> validate UI loop.
                uiAvailability.put(account, new UiAvailability(request.owner, current, validated));
            }
            if (owner(account) == request.owner) {
                NotificationCenter.getInstance(account).postNotificationName(
                        NotificationCenter.lumaLocalGiftsChanged, request.owner);
            }
        });
    }

    private static void discardAvailabilityRequest(int account, UiRequest request) {
        synchronized (availabilityLock) {
            if (uiRequests.get(account) == request) uiRequests.remove(account);
        }
    }

    private static void invalidateAvailability(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
        synchronized (availabilityLock) { invalidateAvailabilityLocked(account); }
    }

    private static void invalidateAvailabilityLocked(int account) {
        availabilityEpochs[account]++;
        uiAvailability.remove(account);
        UiRequest old = uiRequests.remove(account);
        if (old != null) Utilities.globalQueue.cancelRunnable(old.worker);
    }

    private static long owner(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return 0;
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static void requireOwner(int account, long expected) {
        if (expected <= 0 || owner(account) != expected) throw new IllegalStateException("Account changed");
    }

    private static String key(TL_stars.TL_starGiftUnique unique) { return "unique:" + unique.id; }

    private static boolean valid(TL_stars.TL_starGiftUnique unique) {
        return unique != null && unique.id > 0 && unique.title != null && !unique.title.isEmpty()
                && unique.title.length() <= 256 && unique.slug != null && !unique.slug.isEmpty()
                && unique.slug.length() <= 256 && unique.attributes != null
                && unique.attributes.size() <= 32;
    }

    private static void adaptOwner(TL_stars.TL_starGiftUnique unique, long owner) {
        TLRPC.TL_peerUser self = new TLRPC.TL_peerUser();
        self.user_id = owner;
        unique.owner_id = self; // Resolves the current account's name/avatar in LOCAL display only.
        unique.owner_name = null;
        unique.owner_address = null;
        unique.gift_address = null;
        unique.resell_amount = null;
        unique.resale_ton_only = false;
        unique.flags = (unique.flags | 1) & ~(2 | 4 | 8 | 16 | TLObject.FLAG_7);
        unique.attributes.removeIf(attribute -> attribute instanceof TL_stars.starGiftAttributeOriginalDetails);
    }

    private static List<Record> readRecords(int account, long owner) throws Exception {
        requireOwner(account, owner);
        SharedPreferences preferences = LumaAccountData.preferences(account);
        requireOwner(account, owner);
        String encrypted = preferences.getString(STORE, null);
        requireOwner(account, owner);
        if (encrypted != null && encrypted.length() > MAX_ENCRYPTED_CHARS) {
            throw new IllegalArgumentException("Local gift storage too large");
        }
        Cache cached = caches.get(account);
        if (cached != null && cached.owner == owner
                && (encrypted == null ? cached.encrypted == null : encrypted.equals(cached.encrypted))) {
            return cached.records;
        }
        String text = BlackHolePrivateData.read(account, STORE, HEADER);
        requireOwner(account, owner);
        if (text.length() > MAX_STORE_CHARS || !text.startsWith(HEADER)) {
            throw new IllegalArgumentException("Invalid local gift storage");
        }
        ArrayList<Record> result = new ArrayList<>();
        Map<String, Boolean> keys = new HashMap<>();
        int start = HEADER.length();
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0 || end == start || result.size() >= MAX_GIFTS
                    || end - start > ((MAX_GIFT_BYTES + 2) / 3) * 4) {
                throw new IllegalArgumentException("Invalid local gift record");
            }
            byte[] bytes = Base64.decode(text.substring(start, end), Base64.NO_WRAP);
            TL_stars.TL_starGiftUnique gift = decode(bytes, owner);
            String localKey = key(gift);
            if (keys.put(localKey, Boolean.TRUE) != null) throw new IllegalArgumentException("Duplicate local gift");
            result.add(new Record(localKey, bytes));
            start = end + 1;
        }
        requireOwner(account, owner);
        caches.put(account, new Cache(owner, encrypted, result));
        return result;
    }

    private static void writeRecords(int account, long owner, List<Record> records) throws Exception {
        requireOwner(account, owner);
        if (records.size() > MAX_GIFTS) throw new IllegalArgumentException("Too many local gifts");
        StringBuilder text = new StringBuilder(HEADER);
        for (Record record : records) {
            if (text.length() + ((record.bytes.length + 2) / 3) * 4 + 1 > MAX_STORE_CHARS) {
                throw new IllegalArgumentException("Local gift storage too large");
            }
            text.append(Base64.encodeToString(record.bytes, Base64.NO_WRAP)).append('\n');
        }
        BlackHolePrivateData.write(account, owner, STORE, text.toString());
        caches.remove(account);
        final String encrypted;
        try {
            encrypted = encryptedSnapshot(account, owner);
            if (encrypted == null || encrypted.length() > MAX_ENCRYPTED_CHARS) {
                throw new IllegalStateException("Local gift write snapshot unavailable");
            }
        } catch (Exception error) {
            invalidateAvailability(account);
            throw error;
        }
        // These private records were already validated before the encrypted write.
        // Bind that known result to its new owner/ciphertext snapshot immediately:
        // a successful some -> some edit must not briefly hide a local-only Gifts tab.
        List<Record> written = Collections.unmodifiableList(new ArrayList<>(records));
        synchronized (availabilityLock) {
            invalidateAvailabilityLocked(account);
            requireOwner(account, owner);
            uiAvailability.put(account, new UiAvailability(owner, encrypted, !written.isEmpty()));
        }
        caches.put(account, new Cache(owner, encrypted, written));
        requireOwner(account, owner);
    }

    private static byte[] serialize(TL_stars.TL_starGiftUnique gift) {
        BoundedOutput data = new BoundedOutput();
        try {
            gift.serializeToStream(data);
            if (data.failed) throw new IllegalArgumentException("Local gift serialization failed");
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    private static TL_stars.TL_starGiftUnique decode(byte[] bytes, long owner) {
        if (bytes == null || bytes.length < 4 || bytes.length > MAX_GIFT_BYTES) {
            throw new IllegalArgumentException("Invalid local gift size");
        }
        StrictInput data = new StrictInput(bytes);
        try {
            TL_stars.StarGift value = TL_stars.StarGift.TLdeserialize(data, data.readInt32(true), true);
            if (!(value instanceof TL_stars.TL_starGiftUnique) || data.remaining() != 0
                    || !valid((TL_stars.TL_starGiftUnique) value)) {
                throw new IllegalArgumentException("Invalid local unique gift");
            }
            TL_stars.TL_starGiftUnique gift = (TL_stars.TL_starGiftUnique) value;
            // Reject short reads, non-canonical flags and trailing malformed padding.
            if (!Arrays.equals(bytes, serialize(gift))) throw new IllegalArgumentException("Invalid local TL data");
            adaptOwner(gift, owner);
            return gift;
        } finally {
            data.cleanup();
        }
    }

    private static Object cloneGraph(Object value, IdentityHashMap<Object, Object> copied,
                                     int[] objects, int depth) throws Exception {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof Character) return value;
        if (value instanceof String) {
            if (((String) value).length() > 65536) throw new IllegalArgumentException("Oversized TL string");
            return value;
        }
        if (depth > 32 || ++objects[0] > 10000) throw new IllegalArgumentException("Oversized TL graph");
        if (copied.containsKey(value)) return copied.get(value);
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > MAX_GIFT_BYTES) throw new IllegalArgumentException("Oversized TL bytes");
            byte[] clone = bytes.clone();
            copied.put(value, clone);
            return clone;
        }
        if (value instanceof ArrayList) {
            ArrayList<?> list = (ArrayList<?>) value;
            if (list.size() > 256) throw new IllegalArgumentException("Oversized TL list");
            ArrayList<Object> clone = new ArrayList<>(list.size());
            copied.put(value, clone);
            for (Object item : list) clone.add(cloneGraph(item, copied, objects, depth + 1));
            return clone;
        }
        if (!(value instanceof TLObject)) throw new IllegalArgumentException("Unsupported mutable TL field");
        Object clone = value.getClass().getDeclaredConstructor().newInstance();
        copied.put(value, clone);
        for (Field field : value.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            // A standard SVG thumbnail has a runtime Path cache, not a TL wire field.
            // Clone its bytes normally; the real TL decoder regenerates a fresh Path.
            // Do not whitelist arbitrary mutable fields or share the source renderer cache.
            if (value instanceof TLRPC.TL_photoPathSize
                    && field.getDeclaringClass() == TLRPC.TL_photoPathSize.class
                    && field.getName().equals("svgPath")
                    && field.getType() == android.graphics.Path.class) continue;
            field.set(clone, cloneGraph(field.get(value), copied, objects, depth + 1));
        }
        return clone;
    }

    private static final class Record {
        final String key;
        final byte[] bytes;
        Record(String key, byte[] bytes) { this.key = key; this.bytes = bytes; }
    }

    private static final class Cache {
        final long owner;
        final String encrypted;
        final List<Record> records;
        Cache(long owner, String encrypted, List<Record> records) {
            this.owner = owner;
            this.encrypted = encrypted;
            this.records = records;
        }
    }

    private static final class UiAvailability {
        final long owner;
        final String snapshot;
        final boolean hasAny;
        UiAvailability(long owner, String snapshot, boolean hasAny) {
            this.owner = owner; this.snapshot = snapshot; this.hasAny = hasAny;
        }
    }

    private static final class UiRequest {
        final long owner;
        final String snapshot;
        final long epoch;
        Runnable worker;
        UiRequest(long owner, String snapshot, long epoch) {
            this.owner = owner; this.snapshot = snapshot; this.epoch = epoch;
        }
    }

    private static final class StrictInput extends SerializedData {
        private int reads;
        StrictInput(byte[] bytes) { super(bytes); }
        private void require(int bytes) {
            if (++reads > 16384 || bytes < 0 || bytes > remaining()) throw new IllegalArgumentException("Short local TL data");
        }
        @Override public int readInt32(boolean exception) { require(4); return super.readInt32(true); }
        @Override public long readInt64(boolean exception) { require(8); return super.readInt64(true); }
        @Override public byte readByte(boolean exception) { require(1); return super.readByte(true); }
        @Override public byte[] readData(int count, boolean exception) { require(count); return super.readData(count, true); }
        @Override public void readBytes(byte[] bytes, boolean exception) { require(bytes.length); super.readBytes(bytes, true); }
        @Override public byte[] readByteArray(boolean exception) { require(1); return super.readByteArray(true); }
        @Override public String readString(boolean exception) { require(1); return super.readString(true); }
    }

    private static final class BoundedOutput extends SerializedData {
        boolean failed;
        private void require(int bytes) {
            if (bytes < 0 || (long) length() + bytes > MAX_GIFT_BYTES) {
                failed = true;
                throw new IllegalArgumentException("Oversized local TL data");
            }
        }
        private void requireArray(int count) { require((count + (count <= 253 ? 1 : 4) + 3) & ~3); }
        @Override public void writeInt32(int value) { require(4); super.writeInt32(value); }
        @Override public void writeInt64(long value) { require(8); super.writeInt64(value); }
        @Override public void writeByte(int value) { require(1); super.writeByte(value); }
        @Override public void writeByte(byte value) { require(1); super.writeByte(value); }
        @Override public void writeBytes(byte[] bytes) { require(bytes.length); super.writeBytes(bytes); }
        @Override public void writeBytes(byte[] bytes, int offset, int count) { require(count); super.writeBytes(bytes, offset, count); }
        @Override public void writeByteArray(byte[] bytes) { requireArray(bytes.length); super.writeByteArray(bytes); }
        @Override public void writeByteArray(byte[] bytes, int offset, int count) { requireArray(count); super.writeByteArray(bytes, offset, count); }
        @Override public void writeString(String text) { writeByteArray(text.getBytes(StandardCharsets.UTF_8)); }
        @Override public void writeByteBuffer(NativeByteBuffer buffer) { failed = true; throw new IllegalArgumentException("Unsupported TL buffer"); }
    }
}
