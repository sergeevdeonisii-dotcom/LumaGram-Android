import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createHash, randomBytes } from 'node:crypto';
import { createLiveStore } from '../cloudflare/live-store.mjs';
import { TERMS_VERSION } from '../cloudflare/live-control.mjs';
import { createLiveAuth } from '../live-auth.mjs';
import { handleAppRequest } from '../cloudflare/live-http.mjs';
import { CATALOG, SUBSCRIPTION_DURATIONS, addCalendarMonths } from '../catalog.mjs';
import { STARS_TARIFFS, roundedStarsForDuration } from '../stars-tariffs.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW=Date.UTC(2026,0,31,12,34,56,789), ALICE='101', BOB='202';
const KEYS={encryptionKey:Buffer.alloc(32,71),keyPepper:Buffer.alloc(32,72),authPepper:Buffer.alloc(32,73)};
const errorCode=value=>error=>error.code===value;
function fixture(t) {
    const parent=resolve(tmpdir()), directory=mkdtempSync(join(parent,'lunagram-terms-v2-'));
    const file=join(directory,'terms.sqlite'), connections=new Set();
    const state={at:NOW,next:1};
    const open=()=>{
        const storage=openSqliteStorageForTests(file);connections.add(storage);
        const store=createLiveStore(storage,{...KEYS,termsVersion:TERMS_VERSION,clock:()=>state.at});
        const auth=createLiveAuth({storage,authPepper:KEYS.authPepper});
        return {storage,store,auth,clock:()=>state.at,control:{rate(){}},close(){storage.close();connections.delete(storage);}};
    };
    t.after(()=>{for(const storage of connections)storage.close(); assert.equal(dirname(resolve(directory)),parent);
        assert.ok(basename(directory).startsWith('lunagram-terms-v2-'));rmSync(directory,{recursive:true,force:true});});
    return {open,state};
}
const count=(f,table)=>[...f.storage.sql.exec(`SELECT COUNT(*) AS n FROM ${table}`)][0].n;
function delivery(f,id) {
    const row=[...f.storage.sql.exec('SELECT * FROM billing_bot_outbox WHERE id=?',id)][0];
    return f.store.decryptOutbox({id:row.id,kind:row.kind,encryptedPayload:JSON.parse(row.encrypted_payload)});
}
function paid(f,state,{months=3,plan='standard',user=ALICE,legacy=false}={}) {
    const request=`order-${state.next++}`;
    f.store.acceptTerms(user,TERMS_VERSION);
    const order=legacy?f.store.createOrder(user,plan,request):f.store.createOrder(user,plan,request,months);
    assert.equal(f.store.reservePreCheckout({orderId:order.id,userId:user,currency:'XTR',totalAmount:order.starsAmount,queryId:request}).ok,true);
    const chargeId=`charge-${request}`;
    const result=f.store.recordPayment({orderId:order.id,userId:user,currency:'XTR',totalAmount:order.starsAmount,chargeId});
    const text=delivery(f,result.outboxId).text;
    return {...order,chargeId,code:text.match(/\b[A-Z0-9]{16}\b/)[0],receipt:text};
}
function activate(f,value,user=ALICE) {
    const preview=f.store.previewRedeemV2(user,value.code);
    return {preview,result:f.store.confirmRedeemV2(user,preview.quote.quoteId)};
}

test('20 runtime order snapshots retain server price, duration, schema and explicit invoice disclosure',t=>{
    const db=fixture(t),f=db.open();
    for(const plan of CATALOG)for(const duration of SUBSCRIPTION_DURATIONS){
        db.state.at+=61_000;
        const order=paid(f,db.state,{plan:plan.id,months:duration.months});
        assert.equal(order.months,duration.months);assert.equal(order.schemaVersion,2);
        assert.equal(order.starsAmount,roundedStarsForDuration(STARS_TARIFFS[plan.id],duration.months));
        const invoice=delivery(f,order.outboxId);
        assert.match(invoice.description,new RegExp(`${duration.months} мес\\.`));
        assert.match(invoice.description,/до подтверждения активации/);assert.match(invoice.description,/Без автосписаний/);
        assert.equal(invoice.prices[0].amount,order.starsAmount);
        assert.equal(invoice.start_parameter,`buy_${plan.id}_${duration.months}`);
        assert.match(order.receipt,new RegExp(`${duration.months} мес\\.`));
        assert.throws(()=>f.store.redeem(ALICE,order.code),errorCode('UPGRADE_REQUIRED'));
        assert.equal(f.store.listKeys(ALICE).find(k=>k.orderId===order.id).status,'available');
    }
    assert.equal(count(f,'live_order_terms'),20);assert.equal(count(f,'live_fixed_grants'),0);
});

test('duration and schema are idempotency data; invalid/new inputs cannot coerce to monthly legacy',t=>{
    const db=fixture(t),f=db.open();f.store.acceptTerms(ALICE,TERMS_VERSION);
    const first=f.store.createOrder(ALICE,'standard','stable',3);
    assert.deepEqual(f.store.createOrder(ALICE,'standard','stable',3),first);
    assert.throws(()=>f.store.createOrder(ALICE,'standard','stable',1),errorCode('IDEMPOTENCY_CONFLICT'));
    assert.throws(()=>f.store.createOrder(ALICE,'standard','stable'),errorCode('IDEMPOTENCY_CONFLICT'));
    for(const months of [null,'3',0,2,4,13,1.5,Infinity,{}]){
        db.state.at+=61_000;
        assert.throws(()=>f.store.createOrder(ALICE,'standard',`invalid${db.state.at}`,months),errorCode('INVALID_DURATION'));
    }
    assert.equal(count(f,'live_orders'),1);
    assert.equal(first.starsAmount,180);
});

test('preview does not consume key; confirmation atomically binds once and survives lost response/restart',t=>{
    const db=fixture(t);let f=db.open();const value=paid(f,db.state);
    const before=f.store.getSubscriptionV2(ALICE),preview=f.store.previewRedeemV2(ALICE,value.code);
    assert.equal(before.active,false);assert.equal(preview.quote.months,3);
    assert.equal(preview.quote.startsAtMs,NOW);assert.equal(preview.quote.expiresAtMs,addCalendarMonths(NOW,3));
    assert.equal(preview.quote.validUntilMs,NOW+120000);
    assert.equal(f.store.listKeys(ALICE)[0].status,'available');assert.equal(count(f,'live_fixed_grants'),0);
    assert.deepEqual(f.store.previewRedeemV2(ALICE,value.code).quote,preview.quote);
    db.state.at+=30000;
    const first=f.store.confirmRedeemV2(ALICE,preview.quote.quoteId);
    assert.equal(first.serverNowMs,db.state.at);assert.equal(first.queueVersion,1);assert.equal(first.entitlementVersion,1);
    assert.deepEqual(first.current,first.activation);assert.equal(first.current.startsAtMs,NOW);
    assert.equal(count(f,'live_fixed_grants'),1);assert.equal(count(f,'live_grants'),0);
    f.close();f=db.open();db.state.at+=180000;
    const retry=f.store.confirmRedeemV2(ALICE,preview.quote.quoteId);
    assert.deepEqual(retry.activation,first.activation);assert.equal(retry.queueVersion,1);
    assert.equal(count(f,'live_fixed_grants'),1);
    assert.throws(()=>f.store.confirmRedeemV2(BOB,preview.quote.quoteId),errorCode('QUOTE_EXPIRED'));
    assert.throws(()=>f.store.previewRedeemV2(BOB,value.code),errorCode('CODE_ALREADY_USED'));
    const raw=JSON.stringify([...f.storage.sql.exec('SELECT * FROM live_activation_quotes')]);
    assert.ok(!raw.includes(value.code));
});

test('fixed grant, code claim, quote, revision and ledger roll back together on database failure',t=>{
    const db=fixture(t),f=db.open(),value=paid(f,db.state);
    const preview=f.store.previewRedeemV2(ALICE,value.code);
    f.storage.sql.exec(`CREATE TRIGGER fail_v2_ledger BEFORE INSERT ON live_ledger WHEN NEW.kind='redeem'
        BEGIN SELECT RAISE(ABORT,'TEST_ATOMIC_ROLLBACK'); END`);
    assert.throws(()=>f.store.confirmRedeemV2(ALICE,preview.quote.quoteId),/TEST_ATOMIC_ROLLBACK/);
    assert.equal(count(f,'live_fixed_grants'),0);assert.equal(f.store.listKeys(ALICE)[0].status,'available');
    assert.equal(f.store.getSubscriptionV2(ALICE).queueVersion,0);
    assert.equal([...f.storage.sql.exec('SELECT confirmed_ms FROM live_activation_quotes')][0].confirmed_ms,null);
    f.storage.sql.exec('DROP TRIGGER fail_v2_ledger');
    assert.equal(f.store.confirmRedeemV2(ALICE,preview.quote.quoteId).active,true);
});

test('mixed tiers queue immutable non-overlapping dates; status uses current tier not last purchased',t=>{
    const db=fixture(t),f=db.open(),a=paid(f,db.state,{plan:'based',months:1}),b=paid(f,db.state,{plan:'ultra',months:12});
    const first=activate(f,a).result;
    const second=activate(f,b).result;
    assert.equal(second.current.planId,'based');assert.equal(second.queued[0].planId,'ultra');
    assert.equal(second.queued[0].startsAtMs,first.current.expiresAtMs);
    assert.equal(second.paidUntilMs,second.queued[0].expiresAtMs);
    assert.equal(f.store.getSubscription(ALICE).planId,'based');
    db.state.at=first.current.expiresAtMs;
    const later=f.store.getSubscriptionV2(ALICE);
    assert.equal(later.current.planId,'ultra');assert.deepEqual(later.current,second.queued[0]);assert.equal(later.queued.length,0);
    assert.equal(later.queueVersion,2);
    db.state.at=later.paidUntilMs;
    assert.equal(f.store.getSubscriptionV2(ALICE).active,false);
});

test('two pending previews cannot consume against stale queue; fresh preview is explicit',t=>{
    const db=fixture(t),f=db.open(),a=paid(f,db.state),b=paid(f,db.state,{plan:'plus',months:6});
    const qa=f.store.previewRedeemV2(ALICE,a.code),qb=f.store.previewRedeemV2(ALICE,b.code);
    f.store.confirmRedeemV2(ALICE,qa.quote.quoteId);
    assert.throws(()=>f.store.confirmRedeemV2(ALICE,qb.quote.quoteId),errorCode('QUOTE_STALE'));
    assert.equal(f.store.listKeys(ALICE).find(k=>k.orderId===b.id).status,'available');
    const fresh=f.store.previewRedeemV2(ALICE,b.code);
    assert.equal(fresh.quote.startsAtMs,qa.quote.expiresAtMs);assert.notEqual(fresh.quote.quoteId,qb.quote.quoteId);
    f.store.confirmRedeemV2(ALICE,fresh.quote.quoteId);
    assert.equal(count(f,'live_fixed_grants'),2);
});

test('quote expiry and clock rollback reject before code consumption',t=>{
    const db=fixture(t),f=db.open(),value=paid(f,db.state);
    const preview=f.store.previewRedeemV2(ALICE,value.code);
    db.state.at=NOW-1;assert.throws(()=>f.store.confirmRedeemV2(ALICE,preview.quote.quoteId),errorCode('QUOTE_EXPIRED'));
    db.state.at=preview.quote.validUntilMs;assert.throws(()=>f.store.confirmRedeemV2(ALICE,preview.quote.quoteId),errorCode('QUOTE_EXPIRED'));
    assert.equal(f.store.listKeys(ALICE)[0].status,'available');assert.equal(count(f,'live_fixed_grants'),0);
});

test('serialized claims from two independent SQLite connections bind a bearer key to only one owner',async t=>{
    const db=fixture(t),alice=db.open(),bob=db.open(),value=paid(alice,db.state);
    const a=alice.store.previewRedeemV2(ALICE,value.code),b=bob.store.previewRedeemV2(BOB,value.code);
    // Two connections share a real WAL database. Promise scheduling models the
    // DO's serialized transactions, not a claim of multiprocess stress testing.
    const results=await Promise.allSettled([
        Promise.resolve().then(()=>alice.store.confirmRedeemV2(ALICE,a.quote.quoteId)),
        Promise.resolve().then(()=>bob.store.confirmRedeemV2(BOB,b.quote.quoteId)),
    ]);
    assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
    assert.equal(results.find(r=>r.status==='rejected').reason.code,'CODE_ALREADY_USED');
    assert.equal(count(alice,'live_fixed_grants'),1);
    assert.equal(alice.store.getSubscriptionV2(ALICE).active,true);
    assert.equal(bob.store.getSubscriptionV2(BOB).active,false);
    assert.equal(bob.store.getSubscriptionV2(BOB).queueVersion,0);
    assert.equal([...bob.storage.sql.exec('SELECT confirmed_ms FROM live_activation_quotes WHERE id=?',b.quote.quoteId)][0].confirmed_ms,null);
});

test('confirmed refund invalidates other previews without rebasing history; refunded unused key cannot activate',t=>{
    const db=fixture(t),f=db.open(),a=paid(f,db.state,{months:1}),b=paid(f,db.state,{months:6});
    const old=activate(f,a).result.activation,preview=f.store.previewRedeemV2(ALICE,b.code);
    f.store.prepareRefund(a.id);
    assert.equal(f.store.getSubscriptionV2(ALICE).queueVersion,preview.queueVersion);
    f.store.recordRefund({chargeId:a.chargeId});
    assert.throws(()=>f.store.confirmRedeemV2(ALICE,preview.quote.quoteId),errorCode('QUOTE_STALE'));
    const refreshed=f.store.previewRedeemV2(ALICE,b.code);
    assert.equal(refreshed.quote.startsAtMs,old.expiresAtMs); // Do not fill a revoked interval's hole.
    assert.equal(f.store.listKeys(ALICE).find(k=>k.orderId===b.id).status,'available');
    f.store.recordRefund({chargeId:b.chargeId});
    assert.throws(()=>f.store.confirmRedeemV2(ALICE,refreshed.quote.quoteId),errorCode('CODE_UNAVAILABLE'));
    assert.equal(count(f,'live_fixed_grants'),1);
});

test('unknown persisted term schema cannot activate through either legacy or fixed-term paths',t=>{
    const db=fixture(t),f=db.open(),value=paid(f,db.state);
    f.storage.sql.exec('UPDATE live_order_terms SET schema_version=3 WHERE order_id=?',value.id);
    assert.throws(()=>f.store.redeem(ALICE,value.code),errorCode('UPGRADE_REQUIRED'));
    assert.throws(()=>f.store.previewRedeemV2(ALICE,value.code),errorCode('UPGRADE_REQUIRED'));
    assert.equal(count(f,'live_fixed_grants'),0);assert.equal(count(f,'live_grants'),0);
    assert.equal([...f.storage.sql.exec('SELECT redeemed_hash FROM live_codes')][0].redeemed_hash,null);
});

test('pending freezes unused key but keeps activated term; primary refund leaves all other dates intact',t=>{
    const db=fixture(t),f=db.open(),a=paid(f,db.state),b=paid(f,db.state,{plan:'ultra',months:6});
    activate(f,a);const second=activate(f,b).result;
    const original=[...f.storage.sql.exec('SELECT * FROM live_fixed_grants ORDER BY starts_ms')];
    f.store.prepareRefund(a.id);
    assert.equal(f.store.getSubscriptionV2(ALICE).current.orderId,a.id);
    f.store.recordRefund({chargeId:a.chargeId});
    const after=f.store.getSubscriptionV2(ALICE);
    assert.equal(after.active,false);assert.equal(after.current,null);assert.deepEqual(after.queued,second.queued);
    assert.equal(after.paidUntilMs,second.paidUntilMs);
    assert.deepEqual([...f.storage.sql.exec('SELECT * FROM live_fixed_grants ORDER BY starts_ms')],original);
    const revision=after.queueVersion;f.store.recordRefund({chargeId:a.chargeId});assert.equal(f.store.getSubscriptionV2(ALICE).queueVersion,revision);
    db.state.at=b.createdAtMs+61_000;
    const c=paid(f,db.state),quote=f.store.previewRedeemV2(ALICE,c.code);
    f.store.prepareRefund(c.id);assert.throws(()=>f.store.confirmRedeemV2(ALICE,quote.quote.quoteId),errorCode('CODE_UNAVAILABLE'));
    assert.equal(count(f,'live_fixed_grants'),2);
});

test('duplicate charge review refund cannot revoke or shift primary fixed grant',t=>{
    const db=fixture(t),f=db.open(),value=paid(f,db.state);const active=activate(f,value).result;
    f.store.recordPayment({orderId:value.id,userId:ALICE,currency:'XTR',totalAmount:value.starsAmount,chargeId:'second-charge'});
    const review=f.store.listPaymentReviews()[0];f.store.prepareReviewRefund(review.reviewId);f.store.recordRefund({chargeId:'second-charge'});
    assert.equal(f.store.getOrder(value.id).status,'paid');assert.deepEqual(f.store.getSubscriptionV2(ALICE).current,active.current);
    assert.equal(f.store.getSubscriptionV2(ALICE).queueVersion,active.queueVersion);
});

test('active legacy pool is disclosed without fabricated term; v2 fails before consuming until expiry',t=>{
    const db=fixture(t),f=db.open(),old=paid(f,db.state,{legacy:true}),value=paid(f,db.state);
    const original=f.store.redeem(ALICE,old.code);const oldRows=[...f.storage.sql.exec('SELECT * FROM live_grants')];
    const status=f.store.getSubscriptionV2(ALICE);
    assert.equal(status.featuresGated,true,'v2 legacy compatibility still declares actual public tiers');
    assert.equal(status.legacy,true);assert.equal(status.current,null);assert.equal(status.planId,original.planId);
    assert.equal(status.legacyExpiresAtMs,original.expiresAtMs);
    assert.throws(()=>f.store.previewRedeemV2(ALICE,value.code),e=>e.code==='LEGACY_TERM_ACTIVE'&&e.legacyExpiresAtMs===original.expiresAtMs);
    assert.equal(f.store.listKeys(ALICE).find(k=>k.orderId===value.id).status,'available');
    db.state.at=original.expiresAtMs;
    const activated=activate(f,value).result;assert.equal(activated.legacy,undefined);assert.equal(activated.current.startsAtMs,db.state.at);
    assert.deepEqual([...f.storage.sql.exec('SELECT * FROM live_grants')],oldRows);
    const oldUnused=paid(f,db.state,{legacy:true});
    assert.throws(()=>f.store.redeem(ALICE,oldUnused.code),errorCode('UPGRADE_REQUIRED'));
    const fromOldKey=activate(f,oldUnused).result;assert.equal(fromOldKey.activation.months,1);
});

test('an intervening v1 claim invalidates v2 preview rather than reinterpreting its promise',t=>{
    const db=fixture(t),f=db.open(),old=paid(f,db.state,{legacy:true}),value=paid(f,db.state);
    const quote=f.store.previewRedeemV2(ALICE,value.code);
    f.store.redeem(ALICE,old.code);
    assert.throws(()=>f.store.confirmRedeemV2(ALICE,quote.quote.quoteId),errorCode('LEGACY_TERM_ACTIVE'));
    assert.equal(count(f,'live_fixed_grants'),0);
});

test('v2 HTTP uses the same verified pairing session and rejects owner/price/date injection',async t=>{
    const db=fixture(t),f=db.open(),value=paid(f,db.state);
    const verifier=randomBytes(32).toString('base64url'),challenge=createHash('sha256').update(verifier).digest('base64url');
    const pair=f.auth.start({challenge,expectedUserId:ALICE},NOW);f.auth.approve(pair.pairId,ALICE,NOW);
    const {token}=f.auth.exchange(pair.pairId,verifier,NOW);
    const request=(path,body)=>new Request(`https://billing.internal${path}`,{method:'POST',headers:{'Content-Type':'application/json',
        'X-Lunagram-Client-IP':'127.0.0.1',Authorization:`Bearer ${token}`},body:JSON.stringify(body)});
    for(const extra of [{userId:BOB},{startsAtMs:0},{months:12},{starsAmount:1}]){
        assert.equal((await handleAppRequest(request('/v2/redeem/preview',{code:value.code,...extra}),f)).status,400);
    }
    const previewResponse=await handleAppRequest(request('/v2/redeem/preview',{code:value.code}),f);
    assert.equal(previewResponse.status,200);const preview=await previewResponse.json();assert.equal(preview.telegramUserId,ALICE);
    db.state.at+=1000;
    const refreshed=await handleAppRequest(request('/v2/redeem/preview',{code:value.code}),f);
    assert.equal(refreshed.status,200);const fresh=await refreshed.json();
    assert.equal(fresh.quote.startsAtMs,db.state.at);assert.notEqual(fresh.quote.quoteId,preview.quote.quoteId);
    const confirmResponse=await handleAppRequest(request('/v2/redeem/confirm',{quoteId:preview.quote.quoteId}),f);
    assert.equal(confirmResponse.status,200);const result=await confirmResponse.json();assert.equal(result.active,true);
    assert.equal(result.activation.expiresAtMs,preview.quote.expiresAtMs);
    const status=await handleAppRequest(request('/v2/subscription',{}),f);
    assert.equal(status.status,200);assert.equal((await status.json()).current.orderId,value.id);
});
