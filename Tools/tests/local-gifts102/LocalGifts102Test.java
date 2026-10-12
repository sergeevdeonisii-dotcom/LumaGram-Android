package org.telegram.messenger;

import android.util.Base64;
import android.util.Log;
import android.graphics.Path;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalGifts102Test {
    private static final String STORE = "luma_local_unique_gifts_v1";
    private static final String HEADER = "LUMA_LOCAL_GIFTS_1\n";
    private static int checks;

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }
    private static TLRPC.TL_peerUser peer(long id) {
        TLRPC.TL_peerUser p = new TLRPC.TL_peerUser(); p.user_id = id; return p;
    }
    private static TL_stars.TL_starGiftUnique gift(long id) {
        TL_stars.TL_starGiftUnique gift = new TL_stars.TL_starGiftUnique();
        populate(gift, id);
        return gift;
    }
    private static void populate(TL_stars.TL_starGiftUnique gift, long id) {
        gift.id = id; gift.gift_id = 777; gift.num = (int)id;
        gift.title = "Astral Shard"; gift.slug = "AstralShard-" + id;
        gift.flags = 1 | 2 | 4 | 8 | 16 | TLObject.FLAG_6 | TLObject.FLAG_7;
        gift.owner_id = peer(9999); gift.owner_name = "Market seller";
        gift.owner_address = "seller-chain-address"; gift.gift_address = "real-chain-nft-address";
        gift.resell_amount = new ArrayList<>();
        TL_stars.TL_starsAmount price = new TL_stars.TL_starsAmount(); price.amount = 123456;
        gift.resell_amount.add(price);
        gift.availability_issued = 500; gift.availability_total = 1000;
        TL_stars.starGiftAttributeModel model = new TL_stars.starGiftAttributeModel();
        model.flags = 1; model.crafted = false; model.name = "Local model";
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = 456; document.access_hash = 123; document.file_reference = new byte[] {1,2,3,4};
        document.mime_type = "application/x-tgsticker"; document.size = 54321; document.dc_id = 2;
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = "model.tgs"; document.attributes.add(filename);
        model.document = document;
        TL_stars.TL_starGiftAttributeRarity rarity = new TL_stars.TL_starGiftAttributeRarity();
        rarity.permille = 17; model.rarity = rarity;
        gift.attributes.add(model);
        TL_stars.starGiftAttributeBackdrop backdrop = new TL_stars.starGiftAttributeBackdrop();
        backdrop.name = "Midnight"; backdrop.backdrop_id = 99; backdrop.center_color = 0x123456;
        backdrop.edge_color = 0x223344; backdrop.pattern_color = 0xabcdef; backdrop.text_color = 0xffffff;
        backdrop.rarity = new TL_stars.TL_starGiftAttributeRarityRare();
        gift.attributes.add(backdrop);
        TL_stars.starGiftAttributeOriginalDetails history = new TL_stars.starGiftAttributeOriginalDetails();
        history.flags = 1; history.sender_id = peer(3333); history.recipient_id = peer(9999); history.date = 12345;
        gift.attributes.add(history);
    }
    private static void reset() {
        Utilities.globalQueue.clear(); AndroidUtilities.uiQueue.clear(); NotificationCenter.events = 0;
        BlackHolePrivateData.fail = false; BlackHolePrivateData.beforeWrite = null;
        for (int i = 0; i < 4; i++) {
            LumaAccountData.clearOnLogout(i, UserConfig.ids[i]);
            UserConfig.ids[i] = 1001 + i;
            LumaAccountData.preferences(i).edit().clear().apply();
            LumaLocalGifts.clearCache(i);
        }
    }
    private static void putText(String text) throws Exception {
        BlackHolePrivateData.write(0, UserConfig.ids[0], STORE, text);
    }
    private static void expectUnreadable(String text, String label) throws Exception {
        putText(text);
        check(LumaLocalGifts.entries(0).isEmpty(), label + " entries fail closed");
        check(LumaLocalGifts.count(0) == 0, label + " count fail closed");
        check(!LumaLocalGifts.add(0,gift(8)), label + " add must not overwrite corrupted store");
    }

    private static void runValidation() {
        Utilities.globalQueue.runNext();
        AndroidUtilities.uiQueue.runNext();
    }

    private static void availabilityUiTests() throws Exception {
        reset(); check(LumaLocalGifts.add(0,1001,gift(500)), "UI availability setup");
        LumaLocalGifts.clearCache(0);
        int reads = BlackHolePrivateData.reads;
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==1, "cold UI returns false/queues worker");
        for (int i=0;i<100;i++) check(!LumaLocalGifts.hasAnyForUi(0), "pending cold UI fail closed " + i);
        check(Utilities.globalQueue.size()==1 && BlackHolePrivateData.reads==reads, "duplicate UI requests bounded, no UI decrypt");
        Utilities.globalQueue.runNext();
        check(BlackHolePrivateData.reads==reads+1 && AndroidUtilities.uiQueue.size()==1 && NotificationCenter.events==0,
                "worker validates encrypted data, notifies only on UI completion");
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0, "awaiting UI delivery does not enqueue another worker");
        AndroidUtilities.uiQueue.runNext();
        check(LumaLocalGifts.hasAnyForUi(0) && NotificationCenter.events==1
                && NotificationCenter.lastAccount==0 && NotificationCenter.lastOwner==1001, "validated availability event bound to own account");
        reads = BlackHolePrivateData.reads;
        for (int i=0;i<10;i++) check(LumaLocalGifts.hasAnyForUi(0), "hot UI snapshot is cheap " + i);
        check(BlackHolePrivateData.reads==reads && Utilities.globalQueue.size()==0, "cached UI does not decrypt/requeue");
        check(LumaLocalGifts.remove(0,LumaLocalGifts.entries(0).get(0)), "remove real local entry for UI write-through");
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0, "last removal immediately publishes verified false");
        check(NotificationCenter.events==1, "known write result needs no redundant validation event");

        reset(); check(LumaLocalGifts.add(0,gift(501)), "stale preference setup");
        LumaLocalGifts.clearCache(0);
        check(!LumaLocalGifts.hasAnyForUi(0), "stale preference initial request");
        Utilities.globalQueue.runNext();
        LumaAccountData.clearOnLogout(0,1001); // Keep the same fixture owner to test snapshot rather than identity rejection.
        AndroidUtilities.uiQueue.runNext();
        check(NotificationCenter.events==0 && !LumaLocalGifts.hasAnyForUi(0), "same-owner logout prefs change drops true callback");
        runValidation();
        check(!LumaLocalGifts.hasAnyForUi(0) && NotificationCenter.events==1 && Utilities.globalQueue.size()==0,
                "same-owner cleared data becomes validated empty snapshot");

        reset(); check(LumaLocalGifts.add(0,gift(502)), "owner-change UI setup");
        LumaLocalGifts.clearCache(0);
        check(!LumaLocalGifts.hasAnyForUi(0), "owner-change initial request");
        Utilities.globalQueue.runNext();
        UserConfig.ids[0] = 4321;
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==1, "replacement owner queues distinct snapshot");
        AndroidUtilities.uiQueue.runNext();
        check(NotificationCenter.events==0 && Utilities.globalQueue.size()==1, "old-owner callback cannot clear newer request");
        runValidation();
        check(NotificationCenter.events==1 && NotificationCenter.lastOwner==4321 && !LumaLocalGifts.hasAnyForUi(0),
                "only replacement owner's empty availability is published");

        reset(); check(LumaLocalGifts.add(0,gift(503)), "clear-epoch UI setup");
        LumaLocalGifts.clearCache(0);
        check(!LumaLocalGifts.hasAnyForUi(0), "clear-epoch first request");
        Utilities.globalQueue.runNext();
        LumaLocalGifts.clearCache(0);
        AndroidUtilities.uiQueue.runNext();
        check(NotificationCenter.events==0 && !LumaLocalGifts.hasAnyForUi(0), "clearCache epoch cancels late same-owner/snapshot callback");
        runValidation();
        check(LumaLocalGifts.hasAnyForUi(0) && NotificationCenter.events==1, "new epoch revalidates unchanged persistent gift");
        LumaLocalGifts.clearCache(0);
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==1, "pending cancellation setup");
        LumaLocalGifts.clearCache(0);
        check(Utilities.globalQueue.size()==0 && NotificationCenter.events==1, "clearCache removes queued validation runnable");

        reset();
        LumaAccountData.preferences(0).edit().putString(STORE,"not-an-encrypted-gift-store").apply();
        reads = BlackHolePrivateData.reads;
        check(!LumaLocalGifts.hasAnyForUi(0) && BlackHolePrivateData.reads==reads, "corrupt UI snapshot no sync decode");
        runValidation();
        check(!LumaLocalGifts.hasAnyForUi(0) && NotificationCenter.events==1 && Utilities.globalQueue.size()==0,
                "corrupt false result cached, no infinite events");
        for (int i=0;i<20;i++) check(!LumaLocalGifts.hasAnyForUi(0), "corrupt UI stable false " + i);
        check(Utilities.globalQueue.size()==0 && NotificationCenter.events==1, "corrupt snapshot neither loops nor becomes optimistic");
        for (int i=0;i<20;i++) {
            LumaAccountData.preferences(0).edit().putString(STORE,"different-corrupt-snapshot-"+i).apply();
            check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==1, "rapid preference updates bound queue " + i);
        }
        runValidation();
        check(!LumaLocalGifts.hasAnyForUi(0) && NotificationCenter.events==2 && Utilities.globalQueue.size()==0,
                "only latest preference snapshot completes");

        reset();
        CountDownLatch helperHeld = new CountDownLatch(1), releaseHelper = new CountDownLatch(1), uiReturned = new CountDownLatch(1);
        AtomicBoolean uiValue = new AtomicBoolean(true);
        Thread dataWorker = new Thread(() -> {
            synchronized(LumaLocalGifts.class) {
                helperHeld.countDown();
                try { releaseHelper.await(5,TimeUnit.SECONDS); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        dataWorker.setDaemon(true); dataWorker.start();
        check(helperHeld.await(5,TimeUnit.SECONDS), "data helper held for nonblocking UI test");
        Thread uiCaller = new Thread(() -> { uiValue.set(LumaLocalGifts.hasAnyForUi(0)); uiReturned.countDown(); });
        uiCaller.setDaemon(true); uiCaller.start();
        boolean nonblocking = uiReturned.await(2,TimeUnit.SECONDS);
        releaseHelper.countDown(); dataWorker.join(5000); uiCaller.join(5000);
        check(nonblocking && !uiValue.get(), "UI availability does not acquire data/Keystore worker monitor");
        check(!LumaLocalGifts.hasAnyForUi(-1) && !LumaLocalGifts.hasAnyForUi(4), "invalid UI account indices safe");
        UserConfig.ids[0] = 0;
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0, "logged-out UI invalidates queued availability");
    }

    private static void writeThroughAvailabilityTests() throws Exception {
        reset();
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==1, "empty cold validation queued before add");
        check(LumaLocalGifts.add(0,1001,gift(700)), "write-through first add");
        int reads = BlackHolePrivateData.reads;
        check(LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0 && BlackHolePrivateData.reads==reads,
                "successful add immediately exposes verified availability without UI decryption or pending worker");
        check(LumaLocalGifts.add(0,1001,gift(701)), "write-through second add");
        check(LumaLocalGifts.remove(0,LumaLocalGifts.entries(0).get(0)), "write-through remove one of two");
        reads = BlackHolePrivateData.reads;
        check(LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0 && BlackHolePrivateData.reads==reads,
                "some -> some removal never cold-hides Gifts tab");
        check(LumaLocalGifts.remove(0,LumaLocalGifts.entries(0).get(0)), "write-through last removal");
        reads = BlackHolePrivateData.reads;
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0 && BlackHolePrivateData.reads==reads,
                "last removal immediately exposes verified empty with no UI decryption");

        // A previously validated/callback-pending false may not overwrite a later true write.
        LumaLocalGifts.clearCache(0);
        check(!LumaLocalGifts.hasAnyForUi(0), "write-through stale callback setup");
        Utilities.globalQueue.runNext();
        check(LumaLocalGifts.add(0,1001,gift(702)), "add while old empty UI callback pending");
        AndroidUtilities.uiQueue.runNext();
        check(LumaLocalGifts.hasAnyForUi(0) && NotificationCenter.events==0 && Utilities.globalQueue.size()==0,
                "old epoch false completion cannot replace known successful write true");

        // Direct preference changes still invalidate this write-through snapshot; no plaintext count hint is used.
        LumaAccountData.preferences(0).edit().putString(STORE,"externally-corrupted-after-known-write").apply();
        reads = BlackHolePrivateData.reads;
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==1 && BlackHolePrivateData.reads==reads,
                "external corrupt ciphertext never trusts previously-known record count");
        runValidation();
        check(!LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0, "external corruption stays verified false without loops");
        reset();
        BlackHolePrivateData.beforeWrite = () -> UserConfig.ids[0] = 2345;
        check(!LumaLocalGifts.add(0,1001,gift(703)), "failed owner-stamped write-through add");
        check(!LumaLocalGifts.hasAnyForUi(0), "failed/stale write never publishes optimistic availability to replacement owner");
        reset();
    }

    public static final class UniqueWithUnknownCache extends TL_stars.TL_starGiftUnique {
        public Object unknownCache;
    }

    private static void svgThumbnailCloneRegression() throws Exception {
        reset();
        TL_stars.TL_starGiftUnique source = gift(750);
        TL_stars.starGiftAttributeModel model = (TL_stars.starGiftAttributeModel)source.attributes.get(0);
        SerializedData thumbnailData = new SerializedData();
        byte[] shapeBytes = "M0 0L12 12Z".getBytes(StandardCharsets.UTF_8);
        thumbnailData.writeString("j"); thumbnailData.writeByteArray(shapeBytes);
        SerializedData thumbnailInput = new SerializedData(thumbnailData.toByteArray());
        TLRPC.TL_photoPathSize thumbnail = new TLRPC.TL_photoPathSize();
        // Actual production readParams populates svgPath via the JVM SVG renderer adapter.
        thumbnail.readParams(thumbnailInput,true);
        thumbnailInput.cleanup(); thumbnailData.cleanup();
        Path originalPath = thumbnail.svgPath;
        byte[] originalBytes = thumbnail.bytes;
        model.document.flags |= 1; model.document.thumbs.add(thumbnail);
        check(originalPath != null && thumbnail.w==512 && thumbnail.h==512, "real TL SVG thumbnail read populates runtime Path");
        boolean oldFailure = false;
        try { LegacyClone103Probe.copy(source); }
        catch(IllegalArgumentException error) { oldFailure = "Unsupported mutable TL field".equals(error.getMessage()); }
        check(oldFailure, "shipped 1.0.2 clone fails on normal non-null SVG runtime Path negative control");
        Log.warnings.clear();
        int originalFlags = source.flags;
        check(LumaLocalGifts.add(0,1001,source), "fixed clone accepts standard SVG-thumbnail NFT without disabling encryption");
        check(Log.warnings.isEmpty(), "successful standard SVG add emits no failure diagnostic");
        check(source.flags==originalFlags && source.owner_id.user_id==9999 && thumbnail.svgPath==originalPath
                && thumbnail.bytes==originalBytes && Arrays.equals(originalBytes,shapeBytes)
                && originalPath.testShape().equals("M0 0L12 12Z"), "real source/Path/bytes remain unchanged");
        TL_stars.TL_starGiftUnique displayed = (TL_stars.TL_starGiftUnique)LumaLocalGifts.entries(0).get(0).gift;
        TLRPC.TL_photoPathSize displayedThumb = (TLRPC.TL_photoPathSize)
                ((TL_stars.starGiftAttributeModel)displayed.attributes.get(0)).document.thumbs.get(0);
        check(displayed.owner_id.user_id==1001 && displayedThumb != thumbnail && displayedThumb.bytes != originalBytes
                && Arrays.equals(displayedThumb.bytes,originalBytes), "SVG wire bytes deep-cloned and own-owner display retained");
        check(displayedThumb.svgPath != null && displayedThumb.svgPath != originalPath
                && displayedThumb.svgPath.testShape().equals(originalPath.testShape()), "real decoder rebuilds detached renderer Path from saved bytes");
        displayedThumb.bytes[0] = 'X';
        LumaLocalGifts.clearCache(0);
        TL_stars.TL_starGiftUnique reloaded = (TL_stars.TL_starGiftUnique)LumaLocalGifts.entries(0).get(0).gift;
        TLRPC.TL_photoPathSize reloadedThumb = (TLRPC.TL_photoPathSize)
                ((TL_stars.starGiftAttributeModel)reloaded.attributes.get(0)).document.thumbs.get(0);
        check(reloadedThumb.bytes[0]=='M' && reloadedThumb.svgPath != originalPath && reloadedThumb.svgPath != displayedThumb.svgPath,
                "encrypted reload restores fresh SVG bytes and a fresh Path");

        UniqueWithUnknownCache unknown = new UniqueWithUnknownCache(); populate(unknown,751);
        unknown.unknownCache = new Path("PRIVATE_PAYLOAD_NEVER_LOG");
        Log.warnings.clear();
        check(!LumaLocalGifts.add(0,1001,unknown), "Path on any unknown field still fails closed, no blanket mutable-type allowance");
        check(Log.warnings.size()==1 && Log.warnings.get(0).equals("LumaLocalGifts:Local add failed phase=clone class=IllegalArgumentException"),
                "clone diagnostic has only fixed phase and exception class, no IDs/messages/content");
        check(LumaLocalGifts.count(0)==1, "unknown mutable cache never leaves a partial record");
        reset(); BlackHolePrivateData.fail=true;
        Log.warnings.clear();
        check(!LumaLocalGifts.add(0,1001,gift(752)), "Keystore remains fail closed after SVG fix");
        check(Log.warnings.size()==1 && Log.warnings.get(0).equals("LumaLocalGifts:Local add failed phase=storage-write class=IllegalStateException"),
                "Keystore diagnostic sanitized independently from reflection diagnostic");
        check(!LumaAccountData.preferences(0).contains(STORE), "SVG workaround never downgrades encrypted storage");
        reset();
    }

    public static void main(String[] arguments) throws Exception {
        reset();
        TL_stars.TL_starGiftUnique source = gift(11);
        int sourceFlags = source.flags;
        TL_stars.starGiftAttributeModel sourceModel = (TL_stars.starGiftAttributeModel)source.attributes.get(0);
        check(LumaLocalGifts.add(0,source), "valid gift added locally");
        check(source.flags == sourceFlags && source.owner_id.user_id == 9999, "source flags/owner unchanged");
        check(source.owner_name.equals("Market seller") && source.owner_address.equals("seller-chain-address")
                && source.gift_address.equals("real-chain-nft-address"), "source identity/blockchain fields unchanged");
        check(source.resell_amount.size() == 1 && source.attributes.size() == 3 && !sourceModel.crafted,
                "nested attributes/serializer-mutated crafted flag unchanged in real gift");
        check(LumaLocalGifts.hasAny(0) && LumaLocalGifts.count(0) == 1 && LumaLocalGifts.contains(0,source), "availability APIs");
        check(!LumaLocalGifts.add(0,gift(11)) && LumaLocalGifts.count(0) == 1, "duplicates rejected by stable unique ID");
        LumaLocalGifts.LocalSavedGift display = LumaLocalGifts.entries(0).get(0);
        TL_stars.TL_starGiftUnique unique = (TL_stars.TL_starGiftUnique)display.gift;
        check(display.localKey.equals("unique:11") && display.ownerUserId == 1001, "local marker key/owner");
        check(unique.owner_id instanceof TLRPC.TL_peerUser && unique.owner_id.user_id == 1001, "local owner is current user");
        check(unique.owner_name == null && unique.owner_address == null && unique.gift_address == null,
                "old seller and real chain address not attached to simulated ownership");
        check(unique.resell_amount == null && !unique.resale_ton_only && (unique.flags & (2 | 4 | 8 | 16 | TLObject.FLAG_7)) == 0,
                "no resale/action flags retained");
        check(unique.attributes.size() == 2 && unique.attributes.get(1) instanceof TL_stars.starGiftAttributeBackdrop,
                "model/background preserved, old transaction history removed");
        check(display.date == 0 && display.saved_id == -11, "no invented transaction date/server identity");
        check(unique != source && unique.attributes != source.attributes && unique.attributes.get(0) != sourceModel,
                "full gift/attribute isolation");
        TL_stars.starGiftAttributeModel detached = (TL_stars.starGiftAttributeModel)unique.attributes.get(0);
        check(detached.document != sourceModel.document && detached.document.file_reference != sourceModel.document.file_reference,
                "nested document/byte array isolation");
        unique.owner_id.user_id = 666; unique.title = "changed tile";
        detached.document.file_reference[0] = 99; detached.name = "changed model";
        TL_stars.TL_starGiftUnique again = (TL_stars.TL_starGiftUnique)LumaLocalGifts.entries(0).get(0).gift;
        check(again.owner_id.user_id == 1001 && again.title.equals("Astral Shard")
                && ((TL_stars.starGiftAttributeModel)again.attributes.get(0)).document.file_reference[0] == 1,
                "each load restores fresh immutable saved bytes");
        SerializedData output = new SerializedData();
        boolean blocked = false;
        try { display.serializeToStream(output); } catch (UnsupportedOperationException expected) { blocked = true; }
        check(blocked && output.length() == 0, "local wrapper cannot serialize into Telegram transaction");
        output.cleanup();
        String encrypted = LumaAccountData.preferences(0).getString(STORE,null);
        check(encrypted != null && !encrypted.contains("Astral")
                && !new String(Base64.decode(encrypted,Base64.NO_WRAP),StandardCharsets.UTF_8).contains("Astral"),
                "encrypted persistence has no plaintext gift metadata");
        check(LumaLocalGifts.entries(1).isEmpty(), "different account cannot see local collection");
        UserConfig.ids[1] = UserConfig.ids[0];
        check(LumaLocalGifts.count(1) == 1, "same Telegram identity shares identity-scoped collection, not slot");
        UserConfig.ids[1] = 1002;
        LumaLocalGifts.clearCache(0);
        check(LumaLocalGifts.count(0) == 1, "encrypted record survives cache reset/restart");
        check(LumaLocalGifts.remove(0,LumaLocalGifts.entries(0).get(0)) && !LumaLocalGifts.hasAny(0), "local remove persists");
        check(!LumaLocalGifts.remove(0,display), "second remove is a no-op");

        reset();
        TL_stars.TL_starGiftUnique_layer221 legacy = new TL_stars.TL_starGiftUnique_layer221();
        populate(legacy,12);
        check(LumaLocalGifts.add(0,legacy), "legacy-layer unique serializer supported");
        check(LumaLocalGifts.entries(0).get(0).gift instanceof TL_stars.TL_starGiftUnique_layer221,
                "legacy constructor survives serialization round trip");
        LumaLocalGifts.LocalSavedGift oldAccountTile = LumaLocalGifts.entries(0).get(0);
        LumaAccountData.clearOnLogout(0,1001);
        check(!LumaLocalGifts.hasAny(0), "logout clears persistent data and invalidates live cache");
        UserConfig.ids[0] = 5678;
        check(!LumaLocalGifts.remove(0,oldAccountTile) && !LumaLocalGifts.hasAny(0), "stale tile cannot modify reused login slot");
        UserConfig.ids[0] = 0;
        check(!LumaLocalGifts.add(0,gift(20)) && !LumaLocalGifts.hasAny(0), "logged-out account cannot add or read");
        check(!LumaLocalGifts.add(-1,gift(20)) && !LumaLocalGifts.add(4,gift(20)) && LumaLocalGifts.entries(9).isEmpty(),
                "invalid account indices safely rejected");

        reset();
        long initiatingOwner = UserConfig.ids[0];
        UserConfig.ids[0] = 8888;
        check(!LumaLocalGifts.add(0,initiatingOwner,gift(20)) && !LumaLocalGifts.hasAny(0),
                "queued old-account add cannot attach to a new account in the same slot");
        UserConfig.ids[0] = initiatingOwner;
        check(!LumaLocalGifts.hasAny(0), "rejected queued add also leaves old account untouched");
        check(!LumaLocalGifts.add(0,0,gift(20)), "unowned async operation rejected");
        BlackHolePrivateData.beforeWrite = () -> UserConfig.ids[0] = 9876;
        check(!LumaLocalGifts.add(0,gift(21)), "identity change during write fails closed");
        check(!LumaLocalGifts.hasAny(0), "new account did not receive old-account gift");
        UserConfig.ids[0] = 1001;
        check(!LumaLocalGifts.hasAny(0), "old account was not written on raced operation");
        BlackHolePrivateData.fail = true;
        check(!LumaLocalGifts.add(0,gift(22)) && !LumaAccountData.preferences(0).contains(STORE), "Keystore failure never writes plaintext");
        BlackHolePrivateData.fail = false;
        check(LumaLocalGifts.add(0,gift(23)), "store works after adapter becomes available");
        encrypted = LumaAccountData.preferences(0).getString(STORE,null);
        LumaAccountData.preferences(0).edit().putString(STORE,encrypted.substring(0,encrypted.length()/2)).apply();
        check(LumaLocalGifts.entries(0).isEmpty() && !LumaLocalGifts.add(0,gift(24)), "tampering invalidates cached collection");

        reset();
        expectUnreadable("wrong-version\n", "unknown format");
        expectUnreadable(HEADER + "AAAA\n", "truncated constructor");
        expectUnreadable(HEADER + "%%%not-base64%%%\n", "invalid Base64");
        expectUnreadable(HEADER + "AAAA", "missing record delimiter");
        LumaAccountData.preferences(0).edit().putString(STORE,"x".repeat(9*1024*1024+1)).apply();
        check(LumaLocalGifts.entries(0).isEmpty() && !LumaLocalGifts.add(0,gift(30)), "oversized encrypted input rejected before decrypt/decode");
        reset();
        check(!LumaLocalGifts.add(0,null) && !LumaLocalGifts.add(0,gift(0)), "null/zero identity rejected");
        TL_stars.TL_starGiftUnique bad = gift(31); bad.title = "x".repeat(257);
        check(!LumaLocalGifts.add(0,bad), "oversized title rejected");
        bad = gift(32); ((TL_stars.starGiftAttributeModel)bad.attributes.get(0)).document.file_reference = new byte[256*1024+1];
        check(!LumaLocalGifts.add(0,bad), "oversized byte array rejected before serialize");
        bad = gift(33); ((TL_stars.starGiftAttributeModel)bad.attributes.get(0)).document.file_reference = new byte[256*1024];
        check(!LumaLocalGifts.add(0,bad), "bounded output rejects combined payload beyond byte cap");
        check(!LumaLocalGifts.hasAny(0), "invalid inputs never leave partial saved record");

        reset();
        for (int i=1;i<=200;i++) check(LumaLocalGifts.add(0,gift(i)), "bounded capacity item " + i);
        check(LumaLocalGifts.count(0) == 200 && !LumaLocalGifts.add(0,gift(201)), "collection capacity enforced without eviction");
        List<LumaLocalGifts.LocalSavedGift> all = LumaLocalGifts.entries(0);
        check(all.get(0).localKey.equals("unique:200") && all.get(199).localKey.equals("unique:1"), "newest first ordering stable");
        int beforeUiReads = BlackHolePrivateData.reads;
        check(LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0 && BlackHolePrivateData.reads==beforeUiReads,
                "full 200-item successful collection immediately UI-available");
        check(LumaLocalGifts.remove(0,all.get(50)) && LumaLocalGifts.count(0)==199, "full collection remove to 199");
        beforeUiReads = BlackHolePrivateData.reads;
        check(LumaLocalGifts.hasAnyForUi(0) && Utilities.globalQueue.size()==0 && BlackHolePrivateData.reads==beforeUiReads,
                "200 -> 199 remains immediately UI-available, no tab jump/no decryption");
        check(LumaLocalGifts.add(0,gift(201)) && LumaLocalGifts.count(0) == 200,
                "removal frees capacity without touching any other gift");
        availabilityUiTests();
        writeThroughAvailabilityTests();
        svgThumbnailCloneRegression();
        reset();
        check(LumaLocalGifts.add(0,gift(81)), "access fixture stores current allowed local gift");
        String retained = LumaAccountData.preferences(0).getString(STORE,null);
        LunagramSubscriptionAccess.allowed=false;
        check(!LumaLocalGifts.add(0,gift(82)), "denied runtime service creation fails closed");
        check(LumaLocalGifts.entriesForUi(0).isEmpty() && !LumaLocalGifts.hasAnyForUi(0), "expired local profile overlay hidden");
        check(LumaLocalGifts.entries(0).size()==1 && retained.equals(LumaAccountData.preferences(0).getString(STORE,null)), "expiry preserves readable encrypted data");
        check(LumaLocalGifts.remove(0,LumaLocalGifts.entries(0).get(0)), "existing local data remains removable after expiry");
        LunagramSubscriptionAccess.allowed=true;
        System.out.println("PASS: " + checks + " local gift checks, real TL serialization/AES-GCM/isolation/bounds, with JVM Android and key adapters.");
    }
}
