import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { CATALOG, SUBSCRIPTION_DURATIONS, addCalendarMonths } from '../catalog.mjs';
import { createFixedTermGrant, createFixedTermSchedule, withFixedTermRefund, selectFixedTerms } from '../fixed-term-policy.mjs';

const OWNER = 'a'.repeat(64), OTHER = 'b'.repeat(64);
const NOW = Date.UTC(2026, 0, 31, 12, 34, 56, 789);
const code = expected => error => error.code === expected;
const id = (n, length) => n.toString(16).padStart(length, '0');
const input = (n = 1, extra = {}) => ({ grantId: id(n, 64), orderId: id(n, 48), ownerHash: OWNER,
    planId: 'standard', months: 3, startsAtMs: NOW, ...extra });
const grant = (n = 1, extra = {}) => createFixedTermGrant(input(n, extra));

test('all20 plan/duration combinations use existing exact IDs and calendar helper', () => {
    for (const plan of CATALOG) for (const duration of SUBSCRIPTION_DURATIONS) {
        const source = input(1, { planId: plan.id, months: duration.months });
        const before = structuredClone(source), value = createFixedTermGrant(source);
        assert.deepEqual(source, before);
        assert.deepEqual(value, { ...source, expiresAtMs: addCalendarMonths(NOW, duration.months) });
        assert.ok(Object.isFrozen(value));
        assert.throws(() => { value.expiresAtMs += 1; }, TypeError);
    }
    assert.equal(new Date(grant().expiresAtMs).toISOString(), '2026-04-30T12:34:56.789Z');
    assert.equal(new Date(grant(1, { months: 12, startsAtMs: Date.UTC(2024, 1, 29) }).expiresAtMs).toISOString(), '2025-02-28T00:00:00.000Z');
});

test('grant IDs exactly match persisted lowercase hex identity types', () => {
    for (const [field, length, error] of [['grantId',64,'INVALID_GRANT_ID'], ['orderId',48,'INVALID_ORDER_ID'], ['ownerHash',64,'INVALID_OWNER']]) {
        for (const value of [null, undefined, '', 1, 'A'.repeat(length), 'g'.repeat(length), '0'.repeat(length-1), '0'.repeat(length+1), ` ${'0'.repeat(length-1)}`, `${'0'.repeat(length-1)}\n`]) {
            assert.throws(() => createFixedTermGrant(input(1, { [field]: value })), code(error));
        }
    }
});

test('unknown plans, durations, shapes, timestamps and attempted client expiry fail closed', () => {
    for (const planId of [null,undefined,'','Standard','admin','__proto__',{},1]) {
        assert.throws(() => createFixedTermGrant(input(1,{planId})), code('INVALID_PLAN'));
    }
    for (const months of [null,undefined,'1',0,-1,2,4,13,1.5,NaN,Infinity,1n,{},true]) {
        assert.throws(() => createFixedTermGrant(input(1,{months})), code('INVALID_DURATION'));
    }
    for (const startsAtMs of [null,undefined,'0',-1,0.5,NaN,Infinity,-Infinity,Number.MAX_SAFE_INTEGER,8_000_000_000_000_001,0n,new Date()]) {
        assert.throws(() => createFixedTermGrant(input(1,{startsAtMs})), code('INVALID_TIME'));
    }
    assert.throws(() => createFixedTermGrant(input(1,{startsAtMs:8_000_000_000_000_000})), code('INVALID_TIME'));
    assert.equal(createFixedTermGrant(input(1,{months:1,startsAtMs:0})).startsAtMs,0);
    for (const value of [null, [], new Date(), {}, {...input(), expiresAtMs:NOW+1}, {...input(), extra:1}, {...input(), [Symbol('extra')]:true}]) {
        assert.throws(() => createFixedTermGrant(value), code('INVALID_GRANT'));
    }
    const missing = input(); delete missing.months;
    assert.throws(() => createFixedTermGrant(missing), code('INVALID_GRANT'));
    const accessor = input(); Object.defineProperty(accessor,'planId',{get(){throw Error('must not execute');}, enumerable:true});
    assert.throws(() => createFixedTermGrant(accessor), code('INVALID_GRANT'));
});

test('schedule validates immutable stored dates, ownership, duplicate IDs and every historic overlap', () => {
    const first = grant(), second = grant(2,{startsAtMs:first.expiresAtMs});
    const cases = [
        [[{...first, expiresAtMs:first.expiresAtMs+1}], 'INVALID_INTERVAL'],
        [[{...first, expiresAtMs:NaN}], 'INVALID_TIME'],
        [[{...first, ownerHash:OTHER}], 'OWNER_MISMATCH'],
        [[{...first, months:undefined}], 'INVALID_DURATION'],
        [[{...first, extra:true}], 'INVALID_GRANT'],
        [[first,{...second,grantId:first.grantId}], 'DUPLICATE_GRANT'],
        [[first,{...second,orderId:first.orderId}], 'DUPLICATE_ORDER'],
        [[first,grant(2,{startsAtMs:first.expiresAtMs-1})], 'OVERLAPPING_TERMS'],
        [[first,grant(2,{startsAtMs:NOW})], 'OVERLAPPING_TERMS'],
    ];
    for(const [values,error] of cases) assert.throws(()=>createFixedTermSchedule(OWNER,values),code(error));
    assert.throws(()=>createFixedTermSchedule(OWNER,[first,grant(2,{startsAtMs:NOW})],[{orderId:first.orderId,status:'refunded'}]),code('OVERLAPPING_TERMS'));
    for(const bad of [null,{},'terms',new Array(1)]) assert.throws(()=>createFixedTermSchedule(OWNER,bad),code('INVALID_SCHEDULE'));
    const sparseWithExtra = new Array(1); sparseWithExtra.extra = first;
    const symbolExtra = [first]; symbolExtra[Symbol('extra')] = true;
    const accessor = [first]; Object.defineProperty(accessor,'0',{get(){throw Error('must not execute');},enumerable:true});
    for(const bad of [sparseWithExtra,symbolExtra,accessor]) assert.throws(()=>createFixedTermSchedule(OWNER,bad),code('INVALID_SCHEDULE'));
    assert.throws(()=>createFixedTermSchedule('raw-telegram-user',[first]),code('INVALID_OWNER'));
});

test('schedule sorts copies, freezes all returned records and does not mutate caller state', () => {
    const first = grant(), second = grant(2,{startsAtMs:first.expiresAtMs});
    const records = [{...second},{...first}], refunds = [{orderId:second.orderId,status:'refund_pending'}];
    const before = structuredClone({records,refunds});
    const value = createFixedTermSchedule(OWNER,records,refunds);
    assert.deepEqual({records,refunds},before);
    assert.deepEqual(value.grants,[first,second]);
    for(const record of [value,value.grants,value.refunds,...value.grants,...value.refunds]) assert.ok(Object.isFrozen(record));
    records[0].planId='ultra'; refunds[0].status='refunded';
    assert.equal(value.grants[1].planId,'standard'); assert.equal(value.refunds[0].status,'refund_pending');
    assert.throws(()=>value.grants.push(first),TypeError);
    assert.throws(()=>{value.refunds[0].status='refunded';},TypeError);
});

test('active selection is half-open, current-tier only, queued terms remain separate', () => {
    const first = grant(1,{months:1,planId:'based'}), second = grant(2,{startsAtMs:first.expiresAtMs,planId:'ultra'});
    const state=createFixedTermSchedule(OWNER,[first,second]);
    for(const [at,active,queued] of [
        [NOW-1,null,[first,second]], [NOW,first,[second]], [first.expiresAtMs-1,first,[second]],
        [first.expiresAtMs,second,[]], [second.expiresAtMs-1,second,[]], [second.expiresAtMs,null,[]],
    ]) {
        const result=selectFixedTerms(state,at);
        assert.deepEqual(result,{asOfMs:at,activeTerm:active,queued});
        assert.ok(Object.isFrozen(result)&&Object.isFrozen(result.queued));
        assert.deepEqual(state.grants,[first,second]);
    }
    assert.deepEqual(selectFixedTerms(createFixedTermSchedule(OWNER,[]),NOW),{asOfMs:NOW,activeTerm:null,queued:[]});
});

test('repeated or reordered reads never rebase, extend or rewrite a fixed term', () => {
    const first = grant(), second = grant(2,{startsAtMs:first.expiresAtMs});
    const state=createFixedTermSchedule(OWNER,[first,second]);
    const snapshot=structuredClone(state);
    for(const at of [second.expiresAtMs,NOW,NOW-1,first.expiresAtMs,NOW+1]) selectFixedTerms(state,at);
    assert.deepEqual(state,snapshot);
    assert.deepEqual(selectFixedTerms(state,NOW),selectFixedTerms(state,NOW));
    for(const at of [null,undefined,'0',-1,0.5,NaN,Infinity,Number.MAX_SAFE_INTEGER]) {
        assert.throws(()=>selectFixedTerms(state,at),code('INVALID_TIME'));
    }
    assert.throws(()=>selectFixedTerms({...state,expiresAtMs:NOW+1},NOW),code('INVALID_SCHEDULE'));
});

test('pending refund leaves active and queued paid terms intact; confirmed refund removes only its view', () => {
    const first=grant(), second=grant(2,{startsAtMs:first.expiresAtMs,planId:'ultra'});
    const initial=createFixedTermSchedule(OWNER,[first,second]);
    const pending=withFixedTermRefund(initial,first.orderId,'refund_pending');
    assert.deepEqual(selectFixedTerms(pending,NOW),selectFixedTerms(initial,NOW));
    const refunded=withFixedTermRefund(pending,first.orderId,'refunded');
    assert.deepEqual(refunded.grants,initial.grants);
    assert.deepEqual(selectFixedTerms(refunded,NOW),{asOfMs:NOW,activeTerm:null,queued:[second]});
    assert.deepEqual(selectFixedTerms(refunded,second.startsAtMs).activeTerm,second);
    assert.deepEqual(withFixedTermRefund(refunded,first.orderId,'refunded'),refunded);
    assert.throws(()=>withFixedTermRefund(refunded,first.orderId,'refund_pending'),code('REFUND_STATE_REGRESSION'));
    assert.equal(initial.refunds.length,0);
});

test('refunding a queued term preserves current grant and leaves later promised dates untouched', () => {
    const first=grant(), second=grant(2,{startsAtMs:first.expiresAtMs}), third=grant(3,{startsAtMs:second.expiresAtMs});
    const initial=createFixedTermSchedule(OWNER,[first,second,third]);
    const pending=withFixedTermRefund(initial,second.orderId,'refund_pending');
    assert.deepEqual(selectFixedTerms(pending,NOW).queued,[second,third]);
    const confirmed=withFixedTermRefund(pending,second.orderId,'refunded');
    assert.deepEqual(selectFixedTerms(confirmed,NOW),{asOfMs:NOW,activeTerm:first,queued:[third]});
    assert.deepEqual(selectFixedTerms(confirmed,second.startsAtMs),{asOfMs:second.startsAtMs,activeTerm:null,queued:[third]});
    assert.deepEqual(selectFixedTerms(confirmed,third.startsAtMs).activeTerm,third);
    assert.deepEqual(confirmed.grants,[first,second,third]);
});

test('refund input is exact, cannot target an unrelated order or undo a confirmed refund', () => {
    const first=grant(), initial=createFixedTermSchedule(OWNER,[first]);
    assert.throws(()=>withFixedTermRefund(initial,id(99,48),'refunded'),code('REFUND_ORDER_NOT_FOUND'));
    for(const status of [null,undefined,'pending','paid','cancelled',1,true,{}]) {
        assert.throws(()=>withFixedTermRefund(initial,first.orderId,status),code('INVALID_REFUND_STATUS'));
    }
    for(const refunds of [null,{},new Array(1)]) assert.throws(()=>createFixedTermSchedule(OWNER,[first],refunds),code('INVALID_REFUNDS'));
    for(const value of [{orderId:first.orderId},{orderId:first.orderId,status:'refunded',chargeId:'do-not-accept'},null]) {
        assert.throws(()=>createFixedTermSchedule(OWNER,[first],[value]),code('INVALID_REFUND'));
    }
    assert.throws(()=>createFixedTermSchedule(OWNER,[first],[{orderId:id(99,48),status:'refunded'}]),code('REFUND_ORDER_NOT_FOUND'));
    assert.throws(()=>createFixedTermSchedule(OWNER,[first],[{orderId:first.orderId,status:'paid'}]),code('INVALID_REFUND_STATUS'));
    assert.throws(()=>createFixedTermSchedule(OWNER,[first],[{orderId:first.orderId,status:'refund_pending'},{orderId:first.orderId,status:'refunded'}]),code('DUPLICATE_REFUND'));
});

test('only transactional live-store imports the pure policy; no clocks or external effects enter it', () => {
    const root=fileURLToPath(new URL('../',import.meta.url));
    for(const directory of [root,join(root,'cloudflare')]) for(const name of readdirSync(directory)) {
        if(!name.endsWith('.mjs')||name==='fixed-term-policy.mjs'||name==='live-store.mjs') continue;
        assert.doesNotMatch(readFileSync(join(directory,name),'utf8'),/from\s+['"][^'"]*fixed-term-policy\.mjs['"]|import\s*\(['"][^'"]*fixed-term-policy\.mjs['"]\)/);
    }
    const source=readFileSync(join(root,'fixed-term-policy.mjs'),'utf8');
    assert.doesNotMatch(source,/\b(?:fetch|setTimeout|setInterval)\s*\(|Date\.now\s*\(|console\.|node:(?:fs|crypto)|\.transactionSync\s*\(/);
});
