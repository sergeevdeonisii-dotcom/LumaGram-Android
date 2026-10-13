package org.telegram.messenger;

import android.os.SystemClock;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

/** Actual authority, lease, client parser and observer; only Android/storage/transport are modeled. */
public final class Access150Test {
    private static int checks;
    private static final long SERVER=1_800_000_000_000L;
    private static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TOKEN="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final LunagramFeatureCatalog.Feature GHOST=LunagramFeatureCatalog.Feature.GHOST;
    private static final class Wire implements LunagramSubscriptionClient.Transport {
        int calls,cancels; boolean network; Runnable before;
        LunagramSubscriptionClient.Response response;
        public LunagramSubscriptionClient.Response post(String path,String token,JSONObject body) throws Exception {
            calls++;
            if(before!=null) { Runnable r=before; before=null; r.run(); }
            if(network) throw new java.io.IOException("Controlled offline transport");
            if(response==null) throw new AssertionError("Unexpected transport call: "+path);
            return response;
        }
        public void cancel() { cancels++; }
        void ok(JSONObject body) { response=new LunagramSubscriptionClient.Response(200,body); }
    }
    private static void check(boolean condition,String name) {
        checks++; if(!condition) throw new AssertionError(name);
    }
    private static Object field(Object value,String name) throws Exception {
        Field f=value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    private static void set(Object value,String name,Object content) throws Exception {
        Field f=value.getClass().getDeclaredField(name); f.setAccessible(true); f.set(value,content);
    }
    private static Object[] entries() throws Exception {
        Field f=LunagramSubscriptionAccess.class.getDeclaredField("ENTRIES"); f.setAccessible(true); return (Object[])f.get(null);
    }
    private static Object entry(int account) throws Exception {
        Method m=LunagramSubscriptionAccess.class.getDeclaredMethod("entry",int.class); m.setAccessible(true); return m.invoke(null,account);
    }
    private static void tick(int account) throws Exception {
        Object e=entry(account); if(e!=null) ((Runnable)field(e,"tick")).run();
        AndroidUtilities.ready();
    }
    private static void restart() throws Exception {
        for(Object e:entries()) if(e!=null) {
            LunagramSubscriptionClient c=(LunagramSubscriptionClient)field(e,"client"); if(c!=null)c.dispose();
        }
        Arrays.fill(entries(),null); AndroidUtilities.clear(); DispatchQueue.clear();
        Utilities.globalQueue.clear();
        // True cold process semantics for the anti-rollback cache: retain encrypted
        // storage but drop the process-local observation watermark.
        try {
            Field f=LunagramSubscriptionClient.class.getDeclaredField("OBSERVATIONS"); f.setAccessible(true); Arrays.fill((Object[])f.get(null),null);
        } catch(NoSuchFieldException beforeFix) { /* Negative pre-fix reproduction. */ }
    }
    private static void reset() throws Exception {
        LunagramSubscriptionAccess.canUse(0,LunagramFeatureCatalog.Feature.TELEGRAM_BASELINE);
        restart(); BlackHolePrivateData.reset(); LumaBuildPolicy.personal=false; LumaBuildPolicy.privacy=true;
        ApplicationLoader.mainInterfacePaused=true; SystemClock.now=1_000_000L; LunagramSubscriptionClock.boot=7;
        MessagesController.values.clear(); Arrays.fill(MessagesController.ghost,false); Arrays.fill(MessagesController.ghostChanges,0);
        Arrays.fill(NotificationCenter.changes,0); Arrays.fill(NotificationCenter.interfaces,0); Arrays.fill(DownloadController.changes,0); Toast.shown=0;
        Arrays.fill(LunagramMessageReminders.changes,0);
        MessagesController.toastAtGhostDisable=-1; AccessUiTrace.toastShown=0;
        LumaAccountData.preferences(0).edit().putBoolean("luma_ghost_mode_enabled_",true).apply();
    }
    private static void workers() { DispatchQueue.drain(); AndroidUtilities.ready(); }
    private static LunagramSubscriptionClient client(int account,Wire wire) throws Exception {
        LunagramSubscriptionClient c=new LunagramSubscriptionClient(account,wire);
        set(c,"token",TOKEN); set(c,"tokenExpiresAt",System.currentTimeMillis()+7*86_400_000L);
        return c;
    }
    private static JSONObject term(String id,String plan,long start,long end) {
        return new JSONObject().put("orderId",id).put("planId",plan).put("months",1).put("startsAtMs",start).put("expiresAtMs",end);
    }
    private static JSONObject state(int account,String plan,long now,long end,long version) {
        return new JSONObject().put("schemaVersion",2).put("telegramUserId",Long.toString(UserConfig.ids[account]))
                .put("serverNowMs",now).put("entitlementVersion",version).put("queueVersion",version).put("featuresGated",true)
                .put("active",plan!=null).put("planId",plan).put("expiresAtMs",plan==null?0:end).put("paidUntilMs",plan==null?0:end)
                .put("current",plan==null?null:term(A,plan,now-1000,end)).put("queued",new JSONArray());
    }
    private static void fetch(LunagramSubscriptionClient c,Wire w,JSONObject state) {
        w.ok(state); c.refresh(); workers();
        check(c.error()==LunagramSubscriptionClient.Error.NONE,"Valid observed state accepted");
    }
    private static void advance(long by,int account) throws Exception {
        // Run the actual tick only: a delayed authority refresh must not use its real HTTP transport.
        AndroidUtilities.clear(); SystemClock.now+=by; tick(account);
    }
    private static void beginPair(LunagramSubscriptionClient c,Wire w,boolean last) {
        w.ok(new JSONObject().put("pairId","abcdefghijklmnopqrstuv")
                .put("botUrl","https://t.me/LunagramAppPayBot?start=pair_abcdefghijklmnopqrstuv")
                .put("expiresAtMs",System.currentTimeMillis()+600_000));
        c.startAuthentication(); if(last)DispatchQueue.last();else DispatchQueue.one(); AndroidUtilities.ready();
    }
    private static void exchange(LunagramSubscriptionClient c,Wire w,boolean last) {
        w.ok(new JSONObject().put("status","authenticated").put("token",TOKEN).put("telegramUserId",Long.toString(UserConfig.ids[0]))
                .put("expiresAtMs",System.currentTimeMillis()+7*86_400_000L));
        c.checkAuthentication(); if(last)DispatchQueue.last();else DispatchQueue.one(); AndroidUtilities.ready();
        check(c.isAuthenticated(),"Actual pairing establishes a current authenticated client");
    }
    private static void catalogAndLease() throws Exception {
        for(LunagramFeatureCatalog.Tier tier:LunagramFeatureCatalog.Tier.values()) {
            reset(); Wire w=new Wire(); LunagramSubscriptionClient c=client(0,w);
            if(tier!=LunagramFeatureCatalog.Tier.FREE) fetch(c,w,state(0,tier.id,SERVER,SERVER+10_000,1));
            for(LunagramFeatureCatalog.Feature f:LunagramFeatureCatalog.Feature.values())
                check(LunagramSubscriptionAccess.canUse(0,f)==LunagramFeatureCatalog.includes(tier,f),"Actual authority tier membership "+tier+"/"+f);
            check(!LunagramSubscriptionAccess.canUse(0,null),"Null feature is never granted");
            check(LunagramSubscriptionAccess.canUse(0,LunagramFeatureCatalog.Feature.EXISTING_LOCAL_DATA_ACCESS),"Existing local data remains free");
            if(tier!=LunagramFeatureCatalog.Tier.FREE) {
                LunagramEntitlementLease mapped=new LunagramEntitlementLease(c.subscription(),SystemClock.now);
                check(mapped.tier(SystemClock.now)==LunagramFeatureCatalog.Tier.ULTRA,"Every authenticated active former paid tier maps to Ultra: "+tier);
                advance(10_000,0);
                for(LunagramFeatureCatalog.Feature f:LunagramFeatureCatalog.Feature.values())
                    check(LunagramSubscriptionAccess.canUse(0,f)==LunagramFeatureCatalog.isBaselineFree(f),"Every former tier expires at original end: "+tier+"/"+f);
            }
        }
        reset();
        MessagesController.getMainSettings(0).edit().putString("selected_plan","ultra").putString("subscription_tier","ultra").apply();
        for(LunagramSubscriptionCatalog.Period p:LunagramSubscriptionCatalog.PERIODS) {
            LunagramSubscriptionCatalog.Plan.ULTRA.totalUsdCents(p);
            LunagramSubscriptionCatalog.Plan.ULTRA.totalStars(p);
            LunagramSubscriptionClient.purchaseUrl(LunagramSubscriptionCatalog.Plan.ULTRA,p);
        }
        for(LunagramFeatureCatalog.Feature f:LunagramFeatureCatalog.Feature.values())
            check(LunagramSubscriptionAccess.canUse(0,f)==LunagramFeatureCatalog.isBaselineFree(f),"UI selections, preferences and displayed prices cannot create paid authority: "+f);
        reset(); LumaBuildPolicy.personal=true;
        for(LunagramFeatureCatalog.Feature f:LunagramFeatureCatalog.Feature.values()) check(LunagramSubscriptionAccess.canUse(0,f),"Personal bypass "+f);
        check(DispatchQueue.pending()==0 && AndroidUtilities.pending()==0,"Personal bypass does not schedule billing");
        reset(); check(!LunagramSubscriptionAccess.canUse(-1,GHOST)&&!LunagramSubscriptionAccess.canUse(4,GHOST),"Invalid account fails closed");
        UserConfig.ids[0]=0; check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Signed out account fails closed");

        reset(); Wire w=new Wire(); LunagramSubscriptionClient c=client(0,w);
        JSONObject s=state(0,"based",SERVER,SERVER+1000,3).put("queued",new JSONArray().put(term(B,"ultra",SERVER+2000,SERVER+3000))).put("paidUntilMs",SERVER+3000);
        fetch(c,w,s); check(LunagramSubscriptionAccess.canUse(0,GHOST),"Active former Based includes Ultra without consuming future term");
        LunagramEntitlementLease lease=new LunagramEntitlementLease(c.subscription(),SystemClock.now);
        check(lease.nextBoundary(SystemClock.now)==SystemClock.now+1000,"Exact current/next boundary scheduled");
        advance(999,0); check(LunagramSubscriptionAccess.canUse(0,GHOST),"Former tier remains active before exact expiry");
        advance(1,0); check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Catalog migration never fills a gap before queued term");
        advance(1000,0); check(LunagramSubscriptionAccess.canUse(0,GHOST),"Queued immutable term becomes active exactly at original boundary");
        check(MessagesController.ghost[0]&&DownloadController.changes[0]==3,"Effective access changes update privacy and downloads");
        advance(1000,0); check(!LunagramSubscriptionAccess.canUse(0,GHOST),"End-exclusive expiry disables paid tier");
        check(!MessagesController.ghost[0]&&NotificationCenter.changes[0]==4,"Expiry dispatches runtime transition once");
        tick(0); check(NotificationCenter.changes[0]==4,"Repeated ticks do not flood transition notifications");
        check(LunagramMessageReminders.changes[0]==0&&Utilities.globalQueue.pending()==4,"Reminder transitions are queued off the UI thread exactly once per tier change");
        Utilities.globalQueue.drain();
        check(LunagramMessageReminders.changes[0]==4&&LunagramMessageReminders.changes[1]==0,"Reminder transition worker is account scoped");

        reset(); w=new Wire(); c=client(0,w);
        s=state(0,null,SERVER,0,1).put("queued",new JSONArray().put(term(B,"ultra",SERVER+2000,SERVER+4000))).put("paidUntilMs",SERVER+4000);
        fetch(c,w,s); check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Refund gap does not grant a queued tier prematurely");
        advance(2000,0); check(LunagramSubscriptionAccess.canUse(0,GHOST),"Fixed queue starts on original date after a gap");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+3*86_400_000L,1));
        lease=new LunagramEntitlementLease(c.subscription(),SystemClock.now);
        check(lease.tier(SystemClock.now-1)==LunagramFeatureCatalog.Tier.FREE,"Monotonic rollback cannot authorize");
        check(lease.nextBoundary(SystemClock.now)==SystemClock.now+86_400_000L,"Offline lease capped at 24h");
        advance(86_400_000L-1,0); check(LunagramSubscriptionAccess.canUse(0,GHOST),"Offline cached paid state valid just before cap");
        advance(1,0); check(!LunagramSubscriptionAccess.canUse(0,GHOST)&&Toast.shown==0,"Offline cap fails closed without claiming paid term expired");
        check(lease.nextBoundary(SystemClock.now)==1_000_000L+3*86_400_000L,"Expired offline cap does not hide the future paid expiry timer");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1).put("featuresGated",false));
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Old non-authoritative ungated API response does not unlock paid effects");
        for(LunagramSubscriptionCatalog.Plan plan:LunagramSubscriptionCatalog.Plan.values()) {
            reset(); w=new Wire(); c=client(0,w);
            fetch(c,w,state(0,plan.id,SERVER,SERVER+1000,1).put("legacy",true).put("legacyExpiresAtMs",SERVER+1000).put("current",null));
            check(LunagramSubscriptionAccess.canUse(0,GHOST),"Authenticated legacy expiry maps to Ultra: "+plan);
            advance(1000,0); check(!LunagramSubscriptionAccess.canUse(0,GHOST)&&!LumaGhostMode.shouldSuppressActivity(0),"Legacy interval expires without extending or converting into a new term: "+plan);
        }
    }
    private static void validationAndFailures() throws Exception {
        reset(); Wire w=new Wire(); LunagramSubscriptionClient c=client(0,w);
        fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,5));
        w.network=true; c.refresh(); workers(); check(c.error()==LunagramSubscriptionClient.Error.NETWORK&&LunagramSubscriptionAccess.canUse(0,GHOST),"Transient network keeps trusted unexpired authority");
        w.network=false;
        for(JSONObject bad:new JSONObject[]{
            state(0,"based",SERVER+1,SERVER+10_000,4), state(0,"based",SERVER-1,SERVER+10_000,5),
            state(0,"ultra",SERVER,SERVER+10_000,6).put("queued",new JSONArray().put(term(B,"ultra",SERVER+100,SERVER+20_000))).put("paidUntilMs",SERVER+20_000),
            state(0,"ultra",SERVER,SERVER+10_000,6).put("queued",new JSONArray().put(term(A,"ultra",SERVER+10_000,SERVER+20_000))).put("paidUntilMs",SERVER+20_000),
            state(0,"ultra",SERVER,SERVER+10_000,6).put("featuresGated","true")}) {
            w.ok(bad); c.refresh(); workers();
            check(c.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE&&LunagramSubscriptionAccess.canUse(0,GHOST),"Malformed/replayed/overlapping response cannot replace trusted lease");
        }
        Wire lowerWire=new Wire(); LunagramSubscriptionClient lower=client(0,lowerWire);
        lowerWire.ok(state(0,"based",SERVER+1,SERVER+10_000,4)); lower.refresh(); workers();
        check(lower.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE,"Cross-client lower revision rejected before durable persistence");
        check(LunagramSubscriptionAccess.canUse(0,GHOST),"Authority rejects lower revision from a different current client");
        Wire backwardWire=new Wire(); LunagramSubscriptionClient backward=client(0,backwardWire);
        backwardWire.ok(state(0,"based",SERVER-1,SERVER+10_000,6)); backward.refresh(); workers();
        check(backward.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE,"Cross-client older server clock rejected before durable persistence");
        check(LunagramSubscriptionAccess.canUse(0,GHOST),"Authority rejects older server time even with higher revision from another client");
        fetch(lower,lowerWire,state(0,null,SERVER+2,0,6));
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Higher revision refund revokes effects without waiting for paid-until");
        w.ok(state(0,"ultra",SERVER+3,SERVER+10_000,5)); c.refresh(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Older in-flight paid revision cannot resurrect after refund");
        restart(); LunagramSubscriptionClient afterRefund=client(0,new Wire()); afterRefund.restore(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Cold restart cannot restore a late overwritten pre-refund paid snapshot");

        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+1000,1));
        advance(500,0); fetch(c,w,state(0,"ultra",SERVER+500,SERVER+1000,1));
        advance(500,0); check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Repeated receipt/status never extends immutable paid expiry");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1));
        w.response=new LunagramSubscriptionClient.Response(401,new JSONObject().put("error","UNAUTHENTICATED")); c.refresh(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST)&&!c.isAuthenticated(),"401 revokes authorization");
        check(c.subscription()!=null&&c.subscription().expiresAtMs==SERVER+10_000,"401 preserves historical paid dates for display, not authority");
    }
    private static void accountsAndPersistence() throws Exception {
        reset(); Wire w=new Wire(); LunagramSubscriptionClient c=client(0,w);
        beginPair(c,w,false); exchange(c,w,false); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1));
        restart(); Wire restoredWire=new Wire(); LunagramSubscriptionClient restored=new LunagramSubscriptionClient(0,restoredWire);
        restored.restore(); workers();
        check(restoredWire.calls==0&&LunagramSubscriptionAccess.canUse(0,GHOST),"Same boot restores encrypted owner-bound monotonic lease without network");
        check(!restored.subscriptionIsFresh(),"Restored display remains explicitly stale");
        restart(); LunagramSubscriptionClock.boot++; restored=new LunagramSubscriptionClient(0,new Wire()); restored.restore(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST)&&restored.subscription()!=null,"Reboot preserves display but requires network authorization");
        restart(); LunagramSubscriptionClock.boot--; SystemClock.now--;
        restored=new LunagramSubscriptionClient(0,new Wire()); restored.restore(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Future stored monotonic anchor cannot authorize");
        restart(); LunagramSubscriptionClock.boot=-1; SystemClock.now++;
        restored=new LunagramSubscriptionClient(0,new Wire()); restored.restore(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST)&&LumaGhostMode.shouldSuppressActivity(0),"Unavailable boot identity grants no paid access and does not reveal armed privacy");

        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1));
        UserConfig.selectedAccount=1; check(!LunagramSubscriptionAccess.canUse(1,GHOST),"Other signed-in owner never inherits selected owner's tier");
        UserConfig.selectedAccount=0; check(LunagramSubscriptionAccess.canUse(0,GHOST),"Account switch does not erase first owner's valid lease");
        UserConfig.logout(0); UserConfig.ids[0]=101;
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Logout epoch invalidates cached authority even for same returning owner");
        Wire returnedWire=new Wire(); LunagramSubscriptionClient returned=client(0,returnedWire);
        fetch(returned,returnedWire,state(0,"ultra",SERVER+1,SERVER+10_000,2));
        check(LunagramSubscriptionAccess.canUse(0,GHOST),"Same returning owner can establish a new valid epoch after logout");
        reset(); w=new Wire(); c=client(0,w); w.ok(state(0,"ultra",SERVER,SERVER+10_000,1));
        w.before=()->{UserConfig.logout(0);UserConfig.ids[0]=202;}; c.refresh(); workers();
        check(!LunagramSubscriptionAccess.canUse(0,GHOST)&&BlackHolePrivateData.writes==0,"Late network result cannot write or authorize replacement owner");
        reset(); w=new Wire(); c=client(0,w); w.ok(state(0,"ultra",SERVER,SERVER+10_000,1));
        w.before=()->UserConfig.selectedAccount=1; c.refresh(); workers();
        check(!LunagramSubscriptionAccess.canUse(1,GHOST)&&BlackHolePrivateData.writes==0,"Selected-account epoch callback rejects cross-account arrival");
    }
    private static void durableAdmission() throws Exception {
        reset(); Wire freeWire=new Wire(); LunagramSubscriptionClient free=client(0,freeWire);
        fetch(free,freeWire,state(0,null,SERVER+10,0,9));
        restart(); // No live authority or process watermark survives this boundary.
        Wire lateWire=new Wire(); LunagramSubscriptionClient late=client(0,lateWire);
        lateWire.ok(state(0,"ultra",SERVER+11,SERVER+10_000,8)); late.refresh(); workers();
        check(late.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE,"Persisted newer FREE snapshot rejects a lower response before any restore");
        lateWire.ok(state(0,"ultra",SERVER+9,SERVER+10_000,9)); late.refresh(); workers();
        check(late.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE,"Same-version older observed server time cannot overwrite disk");
        fetch(late,lateWire,state(0,null,SERVER+11,0,9));
        check(!LunagramSubscriptionAccess.canUse(0,GHOST),"Same revision later status accepted without manufacturing paid access");
        restart(); LunagramSubscriptionClient restored=client(0,new Wire()); restored.restore(); workers();
        check(restored.subscription()!=null&&restored.subscription().entitlementVersion==9&&!LunagramSubscriptionAccess.canUse(0,GHOST),"Cold restore retains latest validated FREE observation");

        reset(); BlackHolePrivateData.available=false;
        Wire firstWire=new Wire(); LunagramSubscriptionClient first=client(0,firstWire);
        fetch(first,firstWire,state(0,"ultra",SERVER,SERVER+10_000,1));
        Wire refundWire=new Wire(); LunagramSubscriptionClient refund=client(0,refundWire);
        fetch(refund,refundWire,state(0,null,SERVER+1,0,2));
        firstWire.ok(state(0,"ultra",SERVER+2,SERVER+10_000,1)); first.refresh(); workers();
        check(first.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE&&!LunagramSubscriptionAccess.canUse(0,GHOST),"Memory-only clients share monotonic admission even without Keystore persistence");
        UserConfig.logout(0); UserConfig.ids[0]=202;
        Wire replacementWire=new Wire(); LunagramSubscriptionClient replacement=client(0,replacementWire);
        fetch(replacement,replacementWire,state(0,"ultra",SERVER,SERVER+10_000,1));
        check(LunagramSubscriptionAccess.canUse(0,GHOST),"Different owner and new epoch does not inherit previous owner's monotonic watermark");
    }
    private static void revocationRaces() throws Exception {
        reset(); Wire oldWire=new Wire(); LunagramSubscriptionClient old=client(0,oldWire);
        fetch(old,oldWire,state(0,"ultra",SERVER,SERVER+10_000,1));
        Wire revokeWire=new Wire(); LunagramSubscriptionClient revoker=client(0,revokeWire);
        revokeWire.ok(new JSONObject().put("ok",true)); oldWire.ok(state(0,"ultra",SERVER+1,SERVER+10_000,2));
        oldWire.before=()->{revoker.disconnect();DispatchQueue.last();AndroidUtilities.ready();};
        int writes=BlackHolePrivateData.writes; old.refresh(); workers();
        check(!old.isAccountCurrent()&&!LunagramSubscriptionAccess.canUse(0,GHOST),"Global auth revision rejects paid response arriving after revoke");
        check(BlackHolePrivateData.writes==writes,"Stale response after revoke cannot persist a new snapshot");

        reset(); Wire waitingWire=new Wire(); LunagramSubscriptionClient waiting=new LunagramSubscriptionClient(0,waitingWire);
        Object e=entry(0); set(e,"client",waiting); set(e,"restoring",true); waiting.restore();
        Wire pairWire=new Wire(); LunagramSubscriptionClient fresh=new LunagramSubscriptionClient(0,pairWire);
        beginPair(fresh,pairWire,true); exchange(fresh,pairWire,true);
        check(!(boolean)field(e,"restoring")&&field(e,"client")==null,"New pairing retires stale restoring client and clears restoring latch");
        workers(); check(!LunagramSubscriptionAccess.isChecking(0),"Stale restore completion cannot leave permanent checking state");
        fetch(fresh,pairWire,state(0,"ultra",SERVER,SERVER+10_000,1));
        check(LunagramSubscriptionAccess.canUse(0,GHOST),"Fresh authenticated observation recovers after restore/pair race");
    }
    private static void warningAndPreferences() throws Exception {
        reset(); Wire w=new Wire(); LunagramSubscriptionClient c=client(0,w);
        MessagesController.getMainSettings(0).edit().putBoolean("ghost_saved",true).putString("vault_pin","unchanged").putString("local_nft","retained").apply();
        fetch(c,w,state(0,"ultra",SERVER,SERVER+1000,1)); advance(1000,0);
        check(Toast.shown==0,"Background expiry warning waits for visible account");
        ApplicationLoader.mainInterfacePaused=false; tick(0); tick(0);
        check(Toast.shown==1,"Visible expiry warns exactly once for owner and expiry");
        check(MessagesController.getMainSettings(0).getBoolean("ghost_saved",false)
                &&"unchanged".equals(MessagesController.getMainSettings(0).getString("vault_pin",null))
                &&"retained".equals(MessagesController.getMainSettings(0).getString("local_nft",null)),"Expiry never erases preferences, local records or existing vault lock");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+1000,1));
        AndroidUtilities.clear(); SystemClock.now+=1000; ApplicationLoader.mainInterfacePaused=false;
        fetch(c,w,state(0,null,SERVER+1000,0,2));
        check(Toast.shown==1&&!LunagramSubscriptionAccess.canUse(0,GHOST),"Fresh empty server state carries confirmed prior expiry to visible warning");
        check(MessagesController.toastAtGhostDisable==1,"Visible expiry warning occurs before privacy runtime side effects");
    }
    private static void existingPrivacyBarrier() throws Exception {
        reset();
        check(!LumaGhostMode.shouldSuppressActivity(-1)&&!LumaGhostMode.shouldSuppressActivity(4),"Invalid privacy account never reads preferences");
        check(!LumaGhostMode.isEnabled(0)&&LumaGhostMode.shouldSuppressActivity(0),"Cold-start armed ghost protects outgoing activity while paid authority is unresolved");
        check(DispatchQueue.pending()==0,"Privacy getter performs no IO or transport work");
        check(!LumaGhostMode.isAutomaticScheduledSendEnabled(0),"Privacy barrier never grants paid scheduled sending");
        check(LumaGhostMode.shouldBlockEphemeralMedia(0,123,false,true),"Unverified armed ghost cannot send an ephemeral viewing receipt");
        LumaGhostMode.setEnabled(0,false);
        check(!LumaGhostMode.shouldSuppressActivity(0),"Explicit disable releases privacy protection immediately");
        LumaGhostMode.setEnabled(0,true);
        check(!LumaGhostMode.shouldSuppressActivity(0)&&!LumaGhostMode.isEnabled(0),"Unknown authority cannot arm a new ghost preference");
        LumaAccountData.preferences(0).edit().putBoolean("luma_ghost_mode_enabled_",true).apply();
        Wire w=new Wire(); LunagramSubscriptionClient c=client(0,w); w.network=true; c.refresh(); workers();
        check(LumaGhostMode.shouldSuppressActivity(0),"Unknown-network failure never discloses already armed activity");
        w.network=false; fetch(c,w,state(0,null,SERVER,0,1));
        check(!LumaGhostMode.shouldSuppressActivity(0),"Confirmed free status releases barrier without erasing stored preference");
        fetch(c,w,state(0,"ultra",SERVER+1,SERVER+3*86_400_000L,2));
        check(LumaGhostMode.shouldSuppressActivity(0)&&LumaGhostMode.isEnabled(0),"Verified Ultra enables paid ghost normally");
        advance(86_400_000L,0);
        check(!LumaGhostMode.isEnabled(0)&&LumaGhostMode.shouldSuppressActivity(0),"Offline limit removes paid access but does not reveal activity before known paid expiry");
        advance(2*86_400_000L,0);
        check(!LumaGhostMode.isEnabled(0)&&!LumaGhostMode.shouldSuppressActivity(0),"Known final expiry removes paid effects AND privacy fallback");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"based",SERVER,SERVER+3*86_400_000L,1));
        advance(86_400_000L,0); check(!LumaGhostMode.isEnabled(0)&&LumaGhostMode.shouldSuppressActivity(0),"Migrated former tier loses paid access at offline cap but retains the same existing privacy barrier as Ultra");
        advance(2*86_400_000L,0); check(!LumaGhostMode.shouldSuppressActivity(0),"Migrated former tier privacy barrier ends at original paid expiry");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1));
        w.response=new LunagramSubscriptionClient.Response(401,new JSONObject().put("error","UNAUTHENTICATED"));c.refresh();workers();
        check(!LumaGhostMode.isEnabled(0)&&LumaGhostMode.shouldSuppressActivity(0),"401 revokes paid access but is not proof that a paid Ultra interval ended");
        restart(); LunagramSubscriptionClient historical=new LunagramSubscriptionClient(0,new Wire()); historical.restore(); workers();
        check(!LumaGhostMode.isEnabled(0)&&LumaGhostMode.shouldSuppressActivity(0),"Sessionless same-boot history restores privacy only, never paid access");
        advance(10_000,0); check(!LumaGhostMode.shouldSuppressActivity(0),"Sessionless trusted historical expiry still releases fallback on time");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1));
        w.ok(new JSONObject().put("ok",true)); c.disconnect(); workers();
        check(!LumaGhostMode.isEnabled(0)&&!LumaGhostMode.shouldSuppressActivity(0),"Explicit authenticated disconnect releases privacy fallback by user intent");
        reset(); w=new Wire(); c=client(0,w); fetch(c,w,state(0,"ultra",SERVER,SERVER+10_000,1));
        set(c,"tokenExpiresAt",System.currentTimeMillis()-1);c.refresh();workers();
        check(!LumaGhostMode.isEnabled(0)&&LumaGhostMode.shouldSuppressActivity(0),"Session TTL expiry cannot reveal activity during a longer paid subscription");
        reset(); UserConfig.selectedAccount=1;
        check(!LumaGhostMode.shouldSuppressActivity(1),"Different owner without armed preference has no privacy fallback");
        UserConfig.logout(0); UserConfig.ids[0]=202; UserConfig.selectedAccount=0;
        check(!LumaGhostMode.shouldSuppressActivity(0),"Reused slot does not inherit previous owner's privacy preference");
        reset(); LumaBuildPolicy.privacy=false; check(!LumaGhostMode.shouldSuppressActivity(0),"Edition privacy restriction remains authoritative");
    }
    public static void main(String[] args) throws Exception {
        catalogAndLease(); validationAndFailures(); accountsAndPersistence(); durableAdmission(); revocationRaces(); warningAndPreferences(); existingPrivacyBarrier();
        System.out.println("PASS: "+checks+" actual Access/Lease/Client lifecycle, expiry, revision and account-isolation checks; no real network or device.");
    }
}
