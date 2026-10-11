package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Logout102Test {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void user(int account,long owner) {
        TLRPC.User user = new TLRPC.User(); user.id = owner;
        UserConfig.getInstance(account).setCurrentUser(user);
    }
    private static SharedPreferences preferences(long owner) {
        return ApplicationLoader.applicationContext.getSharedPreferences("luma_user_"+owner,Context.MODE_PRIVATE);
    }
    private static TL_stars.TL_starGiftUnique gift(long id) {
        TL_stars.TL_starGiftUnique gift = new TL_stars.TL_starGiftUnique();
        gift.id = id; gift.title = "Local Test Gift"; gift.slug = "LocalTestGift-"+id; gift.num = (int)id;
        return gift;
    }
    private static void reset() {
        Context.applyHook = null;
        for (int account=0;account<UserConfig.MAX_ACCOUNT_COUNT;account++) UserConfig.getInstance(account).clearConfig();
        SharedConfig.clears = 0; BlackHoleVault.locks = 0;
    }
    private static void seedResetFields(UserConfig config) {
        config.sharingMyLocationUntil=100; config.lastMyLocationShareTime=101; config.registeredForPush=true;
        config.contactsSavedCount=102; config.lastSendMessageId=103; config.lastBroadcastId=104;
        config.notificationsSettingsLoaded=true; config.notificationsSignUpSettingsLoaded=true;
        config.migrateOffsetId=1; config.migrateOffsetDate=2; config.migrateOffsetUserId=3;
        config.migrateOffsetChatId=4; config.migrateOffsetChannelId=5; config.migrateOffsetAccess=6;
        config.ratingLoadTime=7; config.botRatingLoadTime=8; config.botGuestRatingLoadTime=9; config.webappRatingLoadTime=10;
        config.draftsLoaded=true; config.contactsReimported=false; config.syncContacts=false;
        config.showCallsTab=true; config.suggestContacts=false; config.unreadDialogsLoaded=false;
        config.hasValidDialogLoadIds=false; config.unacceptedTermsOfService=new Object();
        config.filtersLoaded=true; config.hasSecureData=true;
        config.savedPasswordHash=new byte[] {1,2,3}; config.savedSaltedPassword=new byte[] {4,5,6}; config.savedPasswordTime=123;
    }
    private static void await(CountDownLatch latch,String label) throws Exception {
        check(latch.await(5,TimeUnit.SECONDS),label);
    }
    private static void join(Thread thread,String label) throws Exception {
        thread.join(5000);
        check(!thread.isAlive(),label);
    }

    private static void standardLogout() throws Exception {
        reset(); user(0,1001); user(1,2002);
        UserConfig config = UserConfig.getInstance(0);
        seedResetFields(config);
        byte[] oldHash = config.savedPasswordHash, oldSalt = config.savedSaltedPassword;
        config.getPreferences().edit().putString("test-login","A").apply();
        preferences(1001).edit().putString("private-A","secret A").apply();
        preferences(2002).edit().putString("private-B","secret B").apply();
        ApplicationLoader.applicationContext.getSharedPreferences("luma_logged_out_0",Context.MODE_PRIVATE)
                .edit().putString("orphan","old").apply();
        check(LumaLocalGifts.add(0,1001,gift(1)) && LumaLocalGifts.hasAny(0),"warm real local gift cache");
        int saves = config.saves;
        config.clearConfig();
        check(config.getClientUserId()==0 && !config.isClientActivated() && config.getCurrentUser()==null && config.clientUserId==0,
                "all real identity accessors agree logged out");
        check(preferences(1001).getAll().isEmpty(),"captured owner A preferences actually cleared after identity became zero");
        check("secret B".equals(preferences(2002).getString("private-B",null)),"different-account B data preserved");
        check(config.getPreferences().getAll().isEmpty(),"existing login/config preferences reset preserved");
        check(ApplicationLoader.applicationContext.getSharedPreferences("luma_logged_out_0",Context.MODE_PRIVATE).getAll().isEmpty(),
                "existing logged-out-slot cleanup preserved");
        Field cacheField = LumaLocalGifts.class.getDeclaredField("caches"); cacheField.setAccessible(true);
        check(!((Map<?,?>)cacheField.get(null)).containsKey(0),"gift memory cache discarded explicitly");
        check(config.sharingMyLocationUntil==0 && config.lastMyLocationShareTime==0 && !config.registeredForPush,
                "location and push flags reset preserved");
        check(config.contactsSavedCount==0 && config.lastSendMessageId==-210000 && config.lastBroadcastId==-1,
                "contact and message counters reset preserved");
        check(!config.notificationsSettingsLoaded && !config.notificationsSignUpSettingsLoaded,"notification flags preserved");
        check(config.migrateOffsetId==-1 && config.migrateOffsetDate==-1 && config.migrateOffsetUserId==-1
                && config.migrateOffsetChatId==-1 && config.migrateOffsetChannelId==-1 && config.migrateOffsetAccess==-1,
                "all migration offsets reset preserved");
        check(config.ratingLoadTime==0 && config.botRatingLoadTime==0 && config.botGuestRatingLoadTime==0 && config.webappRatingLoadTime==0,
                "all rating timestamps reset preserved");
        check(!config.draftsLoaded && config.contactsReimported && config.syncContacts && !config.showCallsTab
                && config.suggestContacts && config.unreadDialogsLoaded && config.hasValidDialogLoadIds,
                "all existing default booleans restored");
        check(config.unacceptedTermsOfService==null && !config.filtersLoaded && !config.hasSecureData,"terms/filters/security state reset");
        check(config.savedPasswordHash==null && config.savedSaltedPassword==null && config.savedPasswordTime==0
                && Arrays.equals(oldHash,new byte[3]) && Arrays.equals(oldSalt,new byte[3]),"password bytes zeroed before release");
        int now=(int)(System.currentTimeMillis()/1000);
        check(Math.abs(config.loginTime-now)<=2 && Math.abs(config.lastContactsSyncTime-(now-23*3600))<=2
                && Math.abs(config.lastHintsSyncTime-(now-25*3600))<=2,"existing logout schedule timestamps preserved");
        check(config.saves==saves+1 && config.savedWithFile && config.savedOwner==0,"existing saveConfig(true) sees empty identity");
        check(SharedConfig.clears==0 && UserConfig.getInstance(1).getClientUserId()==2002,"global config not cleared while B remains logged in");
        check(BlackHoleVault.locks==1,"local vault session invalidation retained");
        UserConfig.getInstance(1).clearConfig();
        check(SharedConfig.clears==1,"last account still clears shared config");
        config.clearConfig();
        check(config.getClientUserId()==0 && !LumaLocalGifts.hasAny(0),"repeated already-logged-out clear stays harmless");
    }

    /** Pause after the production encrypted writer's FINAL owner check, at preference apply. */
    private static void racedWriter(String kind) throws Exception {
        reset(); user(0,1001); user(1,2002);
        String key = kind.equals("gifts") ? "luma_local_unique_gifts_v1" : kind.equals("notes") ? "bhg_note_42"
                : kind.equals("subscription") ? "lunagram_subscription_session_v1_prod" : "bhg_notification_journal";
        if(kind.equals("journal")) BlackHoleNotificationJournal.setEnabled(0,true);
        CountDownLatch writePaused=new CountDownLatch(1), releaseWrite=new CountDownLatch(1), logoutFinished=new CountDownLatch(1);
        AtomicBoolean paused=new AtomicBoolean(), heldMonitor=new AtomicBoolean();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Context.applyHook = (store,changes,clear) -> {
            if(!clear && store.equals("luma_user_1001") && changes.containsKey(key) && paused.compareAndSet(false,true)) {
                Class<?> helper=kind.equals("gifts") ? LumaLocalGifts.class : kind.equals("notes") ? BlackHoleNotes.class
                        : kind.equals("subscription") ? LunagramSubscriptionClient.class : BlackHoleNotificationJournal.class;
                heldMonitor.set(Thread.holdsLock(helper));
                writePaused.countDown();
                try { if(!releaseWrite.await(5,TimeUnit.SECONDS)) throw new AssertionError("write release timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
        };
        Thread writer = new Thread(() -> {
            try {
                if(kind.equals("gifts")) LumaLocalGifts.add(0,1001,gift(5));
                else if(kind.equals("subscription")) {
                    new LunagramSubscriptionClient(0).persistForTest("already checked subscription owner A");
                } else if(kind.equals("notes")) {
                    try { BlackHoleNotes.save(0,42,"already checked owner A"); }
                    catch(IllegalStateException expectedIdentityInvalidation) { }
                } else {
                    ArrayList<BlackHoleNotificationJournal.Entry> batch=new ArrayList<>();
                    batch.add(new BlackHoleNotificationJournal.Entry(42,50,System.currentTimeMillis(),"title","already checked owner A"));
                    BlackHoleNotificationJournal.record(0,1001,batch);
                }
            } catch(Throwable error) { failure.compareAndSet(null,error); }
        },"paused-"+kind+"-writer");
        writer.setDaemon(true); writer.start();
        await(writePaused,kind+" reached final preference apply after real owner check");
        check(heldMonitor.get(),kind+" actual write site holds reviewed helper monitor");
        Thread logout = new Thread(() -> {
            try { UserConfig.getInstance(0).clearConfig(); }
            catch(Throwable error) { failure.compareAndSet(null,error); }
            finally { logoutFinished.countDown(); }
        },"logout-during-"+kind+"-write");
        logout.setDaemon(true); logout.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(UserConfig.getInstance(0).getClientUserId()!=0 && System.nanoTime()<deadline) Thread.yield();
        check(UserConfig.getInstance(0).getClientUserId()==0,kind+" identity invalidated BEFORE draining old writer");
        check(logoutFinished.getCount()==1,kind+" logout waits for writer, not cleaning preferences early");
        check(UserConfig.getInstance(0).getCurrentUser()==null,kind+" sync remains available while helper lock drains (no inversion)");
        releaseWrite.countDown();
        join(writer,kind+" old writer exits without deadlock");
        join(logout,kind+" logout completes after writer barrier");
        Context.applyHook=null;
        check(failure.get()==null,kind+" no unexpected concurrent failure: "+failure.get());
        check(preferences(1001).getAll().isEmpty(),kind+" final old-owner clear wins over already-checked write");
        check(UserConfig.getInstance(1).getClientUserId()==2002,kind+" other-account identity preserved");
        user(0,3003);
        check(!LumaLocalGifts.add(0,1001,gift(99)) && !LumaLocalGifts.hasAny(0),kind+" queued old-A gift action cannot target replacement B");
        check(preferences(3003).getString("bhg_note_42",null)==null && preferences(3003).getString(key,null)==null,
                kind+" no encrypted data bleeds into replacement account");
    }

    private static void subscriptionEpochAndIsolation() throws Exception {
        reset(); user(0,1001); user(1,2002);
        final String key = "lunagram_subscription_session_v1_prod";
        LunagramSubscriptionClient old = new LunagramSubscriptionClient(0);
        LunagramSubscriptionClient other = new LunagramSubscriptionClient(1);
        old.persistForTest("old A session"); other.persistForTest("other B session");
        UserConfig.getInstance(0).clearConfig();
        check(preferences(1001).getString(key,null)==null,"subscription persistent token cleared on real logout");
        check("other B session".equals(BlackHolePrivateData.read(1,key,null)),"other account's encrypted subscription stays intact");
        user(0,1001);
        boolean rejected=false;
        try { old.persistForTest("late response for same owner"); } catch(Exception expected) { rejected=true; }
        check(rejected && preferences(1001).getString(key,null)==null,"epoch rejects late response after SAME identity re-login");
        user(0,3003);
        new LunagramSubscriptionClient(0).persistForTest("replacement C session");
        rejected=false;
        try { old.persistForTest("late outgoing owner write"); } catch(Exception expected) { rejected=true; }
        check(rejected && "replacement C session".equals(BlackHolePrivateData.read(0,key,null)),"old client cannot overwrite replacement-account encrypted storage");
        check("other B session".equals(BlackHolePrivateData.read(1,key,null)),"subscription barrier and slot reuse preserve other account");
    }

    public static void main(String[] arguments) throws Exception {
        standardLogout();
        racedWriter("gifts"); racedWriter("notes"); racedWriter("journal"); racedWriter("subscription");
        subscriptionEpochAndIsolation();
        reset(); user(3,4321);
        preferences(4321).edit().putString("private-last-slot","A").apply();
        check(LumaLocalGifts.add(3,4321,gift(4)),"nonzero account slot helper bound to actual UserConfig owner API");
        UserConfig.getInstance(3).clearConfig();
        check(preferences(4321).getAll().isEmpty() && !LumaLocalGifts.hasAny(3),"last-slot owner capture/cleanup correct");
        System.out.println("PASS: "+checks+" extracted UserConfig logout/concurrent writer/lock-order/reset preservation checks.");
    }
}
