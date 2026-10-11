import test from 'node:test';
import assert from 'node:assert/strict';
import { Buffer } from 'node:buffer';
import { createHash, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createLiveAuth, PAIRING_TTL_MS, SESSION_TTL_MS } from '../live-auth.mjs';
import { openSqliteStorageForTests } from './sqlite-storage-fixture.mjs';

const NOW = Date.UTC(2026, 9, 12, 12);
const PEPPER = Buffer.alloc(32, 67); // Public fixture, never a production secret.
const errorCode = code => error => error.code === code;
const pkce = () => {
    const verifier = randomBytes(32).toString('base64url');
    return { verifier, challenge: createHash('sha256').update(verifier, 'ascii').digest('base64url') };
};
function database(t) {
    const parent = resolve(tmpdir());
    const directory = mkdtempSync(join(parent, 'lunagram-auth-'));
    const file = join(directory, 'auth.sqlite');
    const opened = new Set();
    const open = (authPepper = PEPPER) => {
        const storage = openSqliteStorageForTests(file);
        opened.add(storage);
        return { storage, auth: createLiveAuth({ storage, authPepper }), close() { storage.close(); opened.delete(storage); } };
    };
    t.after(() => {
        for (const storage of opened) storage.close();
        assert.equal(dirname(resolve(directory)), parent);
        assert.ok(basename(directory).startsWith('lunagram-auth-'));
        rmSync(directory, { recursive: true, force: true });
    });
    return { open };
}
function start(auth, userId = '101', now = NOW) {
    const proof = pkce();
    return { ...proof, ...auth.start({ challenge: proof.challenge, expectedUserId: userId }, now) };
}
function login(auth, userId = '101', now = NOW) {
    const pair = start(auth, userId, now);
    auth.approve(pair.pairId, userId, now);
    return { ...pair, ...auth.exchange(pair.pairId, pair.verifier, now) };
}

test('auth requires real synchronous SQLite and a server pepper', t => {
    assert.throws(() => createLiveAuth({ authPepper: PEPPER }), errorCode('SQLITE_STORAGE_REQUIRED'));
    const { storage, auth } = database(t).open();
    for (const authPepper of [undefined, 'A'.repeat(32), Buffer.alloc(31)]) {
        assert.throws(() => createLiveAuth({ storage, authPepper }), errorCode('NOT_CONFIGURED'));
    }
    assert.equal(auth.durable, true);
});

test('PKCE requires explicit matching private-user approval, not only the ID hint', t => {
    const { auth } = database(t).open();
    const pair = start(auth);
    assert.match(pair.pairId, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(pair.startParameter, `pair_${pair.pairId}`);
    assert.equal(pair.expiresAtMs, NOW + PAIRING_TTL_MS);
    assert.equal(auth.pending(pair.pairId, '101', NOW).status, 'pending');
    assert.deepEqual(auth.exchange(pair.pairId, pair.verifier, NOW), { status: 'pending', expiresAtMs: pair.expiresAtMs });
    assert.throws(() => auth.approve(pair.pairId, '202', NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    assert.throws(() => auth.pending(pair.pairId, '202', NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    assert.equal(auth.approve(pair.pairId, '101', NOW).status, 'approved');
    const session = auth.exchange(pair.pairId, pair.verifier, NOW);
    assert.equal(session.status, 'authenticated');
    assert.equal(session.telegramUserId, '101');
    assert.equal(session.expiresAtMs, NOW + SESSION_TTL_MS);
    assert.match(session.token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(auth.authenticate(session.token, NOW), '101');
    assert.equal(auth.pending(pair.pairId, '101', NOW).status, 'exchanged');
});

test('private pairing preview does not reveal another account or any proof material', t => {
    const { auth } = database(t).open();
    const pair = start(auth);
    const view = auth.pending(pair.pairId, '101', NOW);
    assert.deepEqual(Object.keys(view).sort(), ['expiresAtMs', 'pairId', 'status']);
    for (const id of [pair.pairId, randomBytes(32).toString('base64url')]) {
        assert.throws(() => auth.pending(id, '202', NOW), errorCode('PAIRING_NOT_AVAILABLE'));
        assert.throws(() => auth.approve(id, '202', NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    }
});

test('PKCE is SHA256 of ASCII verifier, not decoded random bytes or a supplied user ID', t => {
    const { auth } = database(t).open();
    const pair = start(auth);
    auth.approve(pair.pairId, '101', NOW);
    assert.throws(() => auth.exchange(pair.pairId, pkce().verifier, NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    const wrongChallenge = createHash('sha256').update(Buffer.from(pair.verifier, 'base64url')).digest('base64url');
    const other = auth.start({ challenge: wrongChallenge, expectedUserId: '101' }, NOW);
    auth.approve(other.pairId, '101', NOW);
    assert.throws(() => auth.exchange(other.pairId, pair.verifier, NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    assert.throws(() => auth.authenticate('101', NOW), errorCode('UNAUTHENTICATED'));
});

test('lost start response reuses the same link without extending TTL or changing account', t => {
    const { auth } = database(t).open();
    const pair = start(auth);
    const repeated = auth.start({ challenge: pair.challenge, expectedUserId: '101' }, NOW + 1000);
    assert.deepEqual(repeated, { pairId: pair.pairId, expiresAtMs: pair.expiresAtMs, startParameter: pair.startParameter });
    assert.throws(() => auth.start({ challenge: pair.challenge, expectedUserId: '202' }, NOW), errorCode('PAIRING_NOT_AVAILABLE'));
});

test('lost exchange response rotates and revokes atomically, preserving original session expiry', t => {
    const { auth, storage } = database(t).open();
    const first = login(auth);
    const next = auth.exchange(first.pairId, first.verifier, NOW + 5000);
    assert.notEqual(next.token, first.token);
    assert.equal(next.expiresAtMs, first.expiresAtMs);
    assert.throws(() => auth.authenticate(first.token, NOW + 5000), errorCode('UNAUTHENTICATED'));
    assert.equal(auth.authenticate(next.token, NOW + 5000), '101');
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS n FROM billing_auth_sessions')][0].n, 1);
});

test('rotation rollback preserves the prior valid session on a SQL failure', t => {
    const { auth, storage } = database(t).open();
    const first = login(auth);
    storage.sql.exec(`CREATE TRIGGER fail_pair_rotation BEFORE UPDATE OF session_hash ON billing_auth_pairs
        BEGIN SELECT RAISE(ABORT, 'fixture failure'); END`);
    assert.throws(() => auth.exchange(first.pairId, first.verifier, NOW + 1000));
    assert.equal(auth.authenticate(first.token, NOW + 1000), '101');
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS n FROM billing_auth_sessions')][0].n, 1);
});

test('session and pairing state persist across file-backed reopen and concurrent instances', async t => {
    const db = database(t);
    let first = db.open();
    const pair = start(first.auth);
    first.auth.approve(pair.pairId, '101', NOW);
    first.close();
    first = db.open();
    const second = db.open();
    const tokens = await Promise.all([first.auth.exchange(pair.pairId, pair.verifier, NOW),
        second.auth.exchange(pair.pairId, pair.verifier, NOW)]);
    assert.throws(() => first.auth.authenticate(tokens[0].token, NOW), errorCode('UNAUTHENTICATED'));
    assert.equal(first.auth.authenticate(tokens[1].token, NOW), '101');
    first.close(); second.close();
    assert.equal(db.open().auth.authenticate(tokens[1].token, NOW + 1000), '101');
});

test('logout is idempotent and also closes verifier-based recovery of that pairing', t => {
    const { auth } = database(t).open();
    const a = login(auth);
    const b = login(auth, '202');
    assert.deepEqual(auth.revoke(a.token, NOW), { revoked: true });
    assert.deepEqual(auth.revoke(a.token, NOW), { revoked: true });
    assert.deepEqual(auth.revoke(pkce().verifier, NOW), { revoked: true });
    assert.throws(() => auth.authenticate(a.token, NOW), errorCode('UNAUTHENTICATED'));
    assert.throws(() => auth.exchange(a.pairId, a.verifier, NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    assert.throws(() => auth.approve(a.pairId, '101', NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    assert.equal(auth.authenticate(b.token, NOW), '202');
});

test('pairing expires at 10 minutes; established session lasts exactly 30 days', t => {
    const { auth } = database(t).open();
    const a = login(auth);
    assert.equal(auth.authenticate(a.token, a.expiresAtMs - 1), '101');
    assert.throws(() => auth.authenticate(a.token, a.expiresAtMs), errorCode('UNAUTHENTICATED'));
    assert.throws(() => auth.exchange(a.pairId, a.verifier, NOW + PAIRING_TTL_MS), errorCode('PAIRING_NOT_AVAILABLE'));
    assert.throws(() => auth.approve(a.pairId, '101', NOW + PAIRING_TTL_MS), errorCode('PAIRING_NOT_AVAILABLE'));
});

test('database stores only domain-separated session HMACs, never token/verifier/pepper', t => {
    const { auth, storage } = database(t).open();
    const session = login(auth);
    const dump = JSON.stringify(['billing_auth_pairs', 'billing_auth_sessions', 'billing_auth_limits']
        .map(table => [...storage.sql.exec(`SELECT * FROM ${table}`)]));
    for (const secret of [session.token, session.verifier, PEPPER.toString('hex')]) assert.equal(dump.includes(secret), false);
    assert.match([...storage.sql.exec('SELECT token_hash FROM billing_auth_sessions')][0].token_hash, /^[0-9a-f]{64}$/);
});

test('different or subsequently mutated peppers cannot authenticate an existing token', t => {
    const db = database(t);
    const pepper = Buffer.from(PEPPER);
    const a = db.open(pepper);
    const session = login(a.auth);
    pepper.fill(0);
    assert.equal(a.auth.authenticate(session.token, NOW), '101');
    assert.throws(() => db.open(Buffer.alloc(32, 9)).auth.authenticate(session.token, NOW), errorCode('UNAUTHENTICATED'));
});

test('five starts per hinted account per hour, including durable denial after reopen', t => {
    const db = database(t);
    let current = db.open();
    for (let i = 0; i < 5; i++) start(current.auth);
    assert.throws(() => start(current.auth), errorCode('RATE_LIMITED'));
    current.close(); current = db.open();
    assert.throws(() => start(current.auth), errorCode('RATE_LIMITED'));
    assert.ok(start(current.auth, '101', NOW + 3600_000).pairId);
});

test('bad verifier guesses consume durable exchange limit, never rollback it', t => {
    const db = database(t);
    let current = db.open();
    const pair = start(current.auth);
    current.auth.approve(pair.pairId, '101', NOW);
    for (let i = 0; i < 60; i++) {
        assert.throws(() => current.auth.exchange(pair.pairId, pkce().verifier, NOW), errorCode('PAIRING_NOT_AVAILABLE'));
    }
    current.close(); current = db.open();
    assert.throws(() => current.auth.exchange(pair.pairId, pair.verifier, NOW), errorCode('RATE_LIMITED'));
    assert.equal(current.auth.exchange(pair.pairId, pair.verifier, NOW + 60_000).status, 'authenticated');
});

test('global start admission bounds attacker-chosen subject bucket cardinality', t => {
    const { auth, storage } = database(t).open();
    for (let i = 1; i <= 60; i++) start(auth, String(i));
    for (let i = 61; i <= 100; i++) assert.throws(() => start(auth, String(i)), errorCode('RATE_LIMITED'));
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS n FROM billing_auth_limits')][0].n, 61);
});

test('malformed and noncanonical proof material, IDs and timestamps fail closed', t => {
    const { auth } = database(t).open();
    for (const challenge of [null, '', 'x'.repeat(42), 'x'.repeat(44), 'A'.repeat(42) + 'B', "';DROP TABLE x;--"]) {
        assert.throws(() => auth.start({ challenge, expectedUserId: '101' }, NOW), errorCode('INVALID_REQUEST'));
    }
    for (const expectedUserId of [101, '0', '-1', '1.0', '01', "1' OR 1=1", '4503599627370496']) {
        assert.throws(() => auth.start({ challenge: pkce().challenge, expectedUserId }, NOW), errorCode('UNAUTHENTICATED'));
    }
    for (const now of [undefined, null, NaN, Infinity, 0, -1, 0.5, Number.MAX_SAFE_INTEGER]) {
        assert.throws(() => auth.start({ challenge: pkce().challenge, expectedUserId: '101' }, now), errorCode('INVALID_TIME'));
    }
});

test('expired cleanup is bounded and never touches financial history', t => {
    const { auth, storage } = database(t).open();
    storage.sql.exec('CREATE TABLE billing_payments(event_id TEXT PRIMARY KEY,payload TEXT)');
    storage.sql.exec("INSERT INTO billing_payments VALUES ('keep','paid')");
    for (let i = 0; i < 100; i++) {
        storage.sql.exec('INSERT INTO billing_auth_limits VALUES (?,1,?)', `expired-${i}`, NOW - 1);
        storage.sql.exec('INSERT INTO billing_auth_pairs(pair_id,challenge,expected_user_id,created_at_ms,expires_at_ms) VALUES (?,?,?,?,?)',
            `expired-${i}`, `challenge-${i}`, '101', NOW - 2000, NOW - 1);
        storage.sql.exec('INSERT INTO billing_auth_sessions VALUES (?,?,?,?,?)', `hash-${i}`, '101', `expired-${i}`, NOW - 2000, NOW - 1);
    }
    start(auth);
    assert.equal([...storage.sql.exec("SELECT COUNT(*) AS n FROM billing_auth_limits WHERE bucket LIKE 'expired-%'")][0].n, 50);
    assert.equal([...storage.sql.exec("SELECT COUNT(*) AS n FROM billing_auth_pairs WHERE pair_id LIKE 'expired-%'")][0].n, 75);
    assert.equal([...storage.sql.exec("SELECT COUNT(*) AS n FROM billing_auth_sessions WHERE pair_id LIKE 'expired-%'")][0].n, 75);
    assert.equal([...storage.sql.exec('SELECT COUNT(*) AS n FROM billing_payments')][0].n, 1);
});

test('unknown auth schema version fails closed without resetting it', t => {
    const { storage } = database(t).open();
    storage.sql.exec("UPDATE billing_auth_meta SET value=99 WHERE name='schema_version'");
    assert.throws(() => createLiveAuth({ storage, authPepper: PEPPER }), errorCode('UNSUPPORTED_SCHEMA'));
    assert.equal([...storage.sql.exec("SELECT value FROM billing_auth_meta WHERE name='schema_version'")][0].value, 99);
});
