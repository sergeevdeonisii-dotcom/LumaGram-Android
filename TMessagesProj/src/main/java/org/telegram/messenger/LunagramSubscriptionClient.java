package org.telegram.messenger;

import android.util.Base64;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/** Account-bound optional billing. This client never establishes Telegram feature access. */
public final class LunagramSubscriptionClient {
    private static final String ORIGIN = "https://lunagram-billing.pages.dev";
    private static final String BOT = "https://t.me/LunagramAppPayBot?start=";
    private static final String SESSION_KEY = "lunagram_subscription_session_v1_prod";
    private static final String SNAPSHOT_KEY = "lunagram_subscription_snapshot_v2_prod";
    private static final int BODY_LIMIT = 32 * 1024;
    private static final long MAX_TIME = 8_000_000_000_000_000L;
    private static final long FRESH_MS = 15 * 60 * 1000L;
    private static final long PAIR_TTL = 10 * 60 * 1000L;
    private static final long SESSION_TTL = 30 * 24 * 60 * 60 * 1000L;
    private static final DispatchQueue QUEUE = new DispatchQueue("lunagram-billing");
    private static final long[] ACCOUNT_EPOCHS = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] AUTH_REVISIONS = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Observation[] OBSERVATIONS = new Observation[UserConfig.MAX_ACCOUNT_COUNT];
    private static final class Observation {
        final long owner, epoch;
        final Subscription state;
        Observation(long owner, long epoch, Subscription state) {
            this.owner = owner; this.epoch = epoch; this.state = state;
        }
    }

    static synchronized long accountEpoch(int account) { return ACCOUNT_EPOCHS[account]; }
    private static synchronized long authRevision(int account) { return AUTH_REVISIONS[account]; }
    interface StateObserver { void onState(LunagramSubscriptionClient source, AccessSnapshot snapshot); }
    private static volatile StateObserver stateObserver;
    static void setStateObserver(StateObserver observer) { stateObserver = observer; }
    static final class AccessSnapshot {
        final int account;
        final long owner, epoch, receivedElapsed, confirmedEndedAt;
        final Subscription state, privacyHistory;
        final boolean revoked, explicitDisconnect;
        AccessSnapshot(int account, long owner, long epoch, Subscription state, Subscription privacyHistory,
                long receivedElapsed, boolean revoked, boolean explicitDisconnect, long confirmedEndedAt) {
            this.account = account; this.owner = owner; this.epoch = epoch; this.state = state;
            this.receivedElapsed = receivedElapsed; this.revoked = revoked;
            this.privacyHistory = privacyHistory; this.explicitDisconnect = explicitDisconnect;
            this.confirmedEndedAt = confirmedEndedAt;
        }
    }

    /** Called after UserConfig publishes an empty identity, before clearing its data.
     * The class monitor drains writes past their final owner check; never acquire
     * a client-instance lock here. Epochs also reject a late response after the
     * same Telegram user signs into the reused slot again. */
    public static synchronized void onAccountLogout(int account) {
        ACCOUNT_EPOCHS[account]++;
        OBSERVATIONS[account] = null;
    }

    public enum Error { NONE, NETWORK, SERVER, BAD_RESPONSE, ACCOUNT_CHANGED, AUTH_REQUIRED,
        EXPIRED, STORAGE, INVALID_CODE, RATE_LIMITED, NOT_CONFIGURED, UPGRADE_REQUIRED,
        LEGACY_TERM_ACTIVE, QUOTE_EXPIRED, QUOTE_STALE, QUEUE_FULL }
    public interface Listener { void onChanged(); }
    interface Transport {
        Response post(String path, String token, JSONObject body) throws Exception;
        void cancel();
    }
    static final class Response {
        final int status;
        final JSONObject body;
        Response(int status, JSONObject body) { this.status = status; this.body = body; }
    }
    public static final class Term {
        public final String orderId, planId;
        public final int months;
        public final long startsAtMs, expiresAtMs;
        Term(String orderId, String planId, int months, long startsAtMs, long expiresAtMs) {
            this.orderId = orderId; this.planId = planId; this.months = months;
            this.startsAtMs = startsAtMs; this.expiresAtMs = expiresAtMs;
        }
    }
    public static final class Quote {
        public final String quoteId, planId;
        public final int months, starsAmount;
        public final long startsAtMs, expiresAtMs, validUntilMs, queueVersion;
        Quote(String quoteId, String planId, int months, int starsAmount, long startsAtMs,
                long expiresAtMs, long validUntilMs, long queueVersion) {
            this.quoteId = quoteId; this.planId = planId; this.months = months; this.starsAmount = starsAmount;
            this.startsAtMs = startsAtMs; this.expiresAtMs = expiresAtMs;
            this.validUntilMs = validUntilMs; this.queueVersion = queueVersion;
        }
    }
    /** A server observation, not an offline authorization to change feature access. */
    public static final class Subscription {
        public final String planId;
        public final long expiresAtMs, serverNowMs, paidUntilMs, entitlementVersion, legacyExpiresAtMs;
        public final boolean active, legacy, featuresGated;
        public final Term current;
        public final List<Term> queued;
        Subscription(String planId, long expiresAtMs, boolean active, long serverNowMs,
                long paidUntilMs, long version, Term current, List<Term> queued, boolean legacy, boolean featuresGated) {
            this.planId = planId; this.expiresAtMs = expiresAtMs; this.active = active;
            this.serverNowMs = serverNowMs; this.paidUntilMs = paidUntilMs; this.entitlementVersion = version;
            this.current = current; this.queued = Collections.unmodifiableList(new ArrayList<>(queued));
            this.legacy = legacy; this.legacyExpiresAtMs = legacy ? expiresAtMs : 0;
            this.featuresGated = featuresGated;
        }
    }
    private enum Operation { RESTORE, START, EXCHANGE, STATUS, PREVIEW, CONFIRM, REVOKE }
    private final Object lock = new Object();
    private final int account;
    private final long owner;
    private final long accountEpoch;
    private long authRevision;
    private final Transport transport;
    private Listener listener;
    private long generation;
    private boolean disposed, busy;
    private Operation activeOperation;
    private String token, pairId;
    private long tokenExpiresAt, pairExpiresAt;
    private long lastExchangeNanos;
    private byte[] verifier;
    private Subscription subscription;
    private Quote quote;
    private boolean freshSnapshot, hasServerAnchor, confirmationSubmitted;
    private long snapshotReceivedElapsed;
    private boolean accessRevoked;
    private boolean explicitDisconnect;
    private long confirmedEndedAt;
    private Error error = Error.NONE;

    public LunagramSubscriptionClient(int account, Listener listener) {
        this(account, new HttpsTransport());
        this.listener = listener;
    }
    LunagramSubscriptionClient(int account, Transport transport) {
        this.account = account;
        this.accountEpoch = accountEpoch(account);
        this.authRevision = authRevision(account);
        this.owner = UserConfig.getInstance(account).getClientUserId();
        this.transport = transport;
    }
    public boolean isBusy() { synchronized (lock) { return busy; } }
    public boolean isAuthenticated() { synchronized (lock) { return token != null && tokenExpiresAt > System.currentTimeMillis() && currentOwner(); } }
    public boolean isPairing() { synchronized (lock) { return pairId != null && pairExpiresAt > System.currentTimeMillis() && currentOwner(); } }
    public long pairExpiresAtMs() { synchronized (lock) { return pairExpiresAt; } }
    public Error error() { synchronized (lock) { return error; } }
    public Subscription subscription() { synchronized (lock) { return currentOwner() ? subscription : null; } }
    public Quote quote() { synchronized (lock) { return currentOwner() ? quote : null; } }
    public boolean subscriptionIsFresh() {
        synchronized (lock) {
            return currentOwner() && freshSnapshot && subscription != null && isAuthenticated()
                    && SystemClock.elapsedRealtime() - snapshotReceivedElapsed < FRESH_MS;
        }
    }
    public long estimatedServerNowMs() {
        synchronized (lock) {
            if (!currentOwner() || subscription == null || !hasServerAnchor) return 0;
            return Math.min(MAX_TIME, subscription.serverNowMs + Math.max(0, SystemClock.elapsedRealtime() - snapshotReceivedElapsed));
        }
    }
    public void clearQuote() {
        boolean cancel;
        synchronized (lock) {
            quote = null; confirmationSubmitted = false;
            cancel = busy && (activeOperation == Operation.PREVIEW || activeOperation == Operation.CONFIRM);
            if (cancel) { generation++; busy = false; }
        }
        if (cancel) { transport.cancel(); notifyChanged(); }
    }
    public boolean isPersistentStorageAvailable() { return BlackHolePrivateData.isAvailable(); }
    public boolean isAccountCurrent() { synchronized (lock) { return currentOwner(); } }
    public boolean isTestAccount() { return ConnectionsManager.getInstance(account).isTestBackend(); }
    public String authenticationUrl() {
        synchronized (lock) { return isPairing() ? BOT + "pair_" + pairId : null; }
    }
    public static String purchaseUrl(LunagramSubscriptionCatalog.Plan plan) {
        return plan == null ? null : BOT + "buy_" + plan.id;
    }
    public static String purchaseUrl(LunagramSubscriptionCatalog.Plan plan, LunagramSubscriptionCatalog.Period period) {
        return plan == null || period == null ? null : BOT + "buy_" + plan.id + "_" + period.months;
    }
    public void restore() { submit(Operation.RESTORE, null); }
    public void startAuthentication() { submit(Operation.START, null); }
    public void checkAuthentication() { submit(Operation.EXCHANGE, null); }
    public void refresh() { submit(Operation.STATUS, null); }
    public void previewCode(CharSequence input) {
        synchronized (lock) {
            // Do not destroy the recovery token of an in-flight confirmation.
            if (disposed || busy) return;
            String code = LunagramSubscriptionCatalog.normalizeActivationCode(input);
            if (code == null) { quote = null; error = Error.INVALID_CODE; notifyChanged(); return; }
            quote = null; confirmationSubmitted = false;
            submit(Operation.PREVIEW, code);
        }
    }
    public void confirmCode() {
        String id;
        synchronized (lock) {
            if (disposed || busy) return;
            if (quote == null || (!confirmationSubmitted && (estimatedServerNowMs() == 0 || estimatedServerNowMs() >= quote.validUntilMs))) {
                quote = null; error = Error.QUOTE_EXPIRED; notifyChanged(); return;
            }
            id = quote.quoteId;
            // A lost response can follow a committed activation. Retrying the SAME
            // quote is safe even after its original validity; the server is idempotent.
            confirmationSubmitted = true;
        }
        submit(Operation.CONFIRM, id);
    }
    public void disconnect() { submit(Operation.REVOKE, null); }

    /** Leaving for the bot cancels transport, but keeps its expiring verifier in memory. */
    public void pause() {
        synchronized (lock) { generation++; busy = false; }
        transport.cancel();
    }
    public void cancelAuthentication() {
        pause();
        synchronized (lock) { clearPair(); error = Error.NONE; }
        notifyChanged();
    }
    public void dispose() {
        pause();
        synchronized (lock) {
            disposed = true; clearPair(); token = null; subscription = null; quote = null; freshSnapshot = false; listener = null;
        }
    }
    private boolean currentOwner() {
        return accountEpoch == accountEpoch(account) && authRevision == authRevision(account)
                && owner > 0 && UserConfig.selectedAccount == account
                && !isTestAccount()
                && UserConfig.getInstance(account).getClientUserId() == owner;
    }
    private void clearPair() {
        if (verifier != null) Arrays.fill(verifier, (byte) 0);
        verifier = null; pairId = null; pairExpiresAt = 0;
    }
    private boolean current(long ticket) { return !disposed && ticket == generation && currentOwner(); }
    private void submit(Operation operation, String code) {
        final long ticket;
        synchronized (lock) {
            if (disposed || busy) return;
            if (!currentOwner()) { clearPair(); token = null; subscription = null; quote = null; freshSnapshot = false; error = Error.ACCOUNT_CHANGED; notifyChanged(); return; }
            if (operation == Operation.EXCHANGE) {
                long now = System.nanoTime();
                if (lastExchangeNanos != 0 && now - lastExchangeNanos < 1_000_000_000L) return;
                lastExchangeNanos = now;
            }
            busy = true; activeOperation = operation; error = Error.NONE; ticket = ++generation;
        }
        notifyChanged();
        QUEUE.postRunnable(() -> perform(ticket, operation, code));
    }
    private void perform(long ticket, Operation operation, String code) {
        try {
            String session;
            JSONObject body = new JSONObject();
            String path;
            synchronized (lock) {
                if (!current(ticket)) return;
                if (operation == Operation.RESTORE) {
                    restoreSnapshot(ticket); restoreSession(); busy = false; notifyChanged(); return;
                }
                session = token;
                if (operation == Operation.START) {
                    clearPair();
                    byte[] entropy = new byte[32];
                    new SecureRandom().nextBytes(entropy);
                    verifier = encode(entropy).getBytes(StandardCharsets.US_ASCII);
                    Arrays.fill(entropy, (byte) 0);
                    body.put("challenge", challenge(verifier));
                    body.put("expectedUserId", Long.toString(owner));
                    path = "/v1/auth/start";
                } else if (operation == Operation.EXCHANGE) {
                    if (pairId == null || verifier == null || pairExpiresAt <= System.currentTimeMillis()) {
                        clearPair(); throw new Failure(Error.EXPIRED);
                    }
                    body.put("pairId", pairId);
                    body.put("verifier", new String(verifier, StandardCharsets.US_ASCII));
                    path = "/v1/auth/exchange";
                } else {
                    if (session == null || tokenExpiresAt <= System.currentTimeMillis()) throw new Failure(Error.AUTH_REQUIRED);
                    path = operation == Operation.PREVIEW ? "/v2/redeem/preview"
                            : operation == Operation.CONFIRM ? "/v2/redeem/confirm"
                            : operation == Operation.REVOKE ? "/v1/auth/revoke" : "/v2/subscription";
                    if (operation == Operation.PREVIEW) body.put("code", code);
                    if (operation == Operation.CONFIRM) body.put("quoteId", code);
                }
            }
            Response response = transport.post(path,
                    operation == Operation.START || operation == Operation.EXCHANGE ? null : session, body);
            synchronized (lock) {
                if (!current(ticket)) return;
                if (response.status == 401) { clearSession(); throw new Failure(Error.AUTH_REQUIRED); }
                if (response.status != 200 && !(operation == Operation.EXCHANGE && response.status == 202)) {
                    throw new Failure(serverError(response));
                }
                if (operation == Operation.START) {
                    String id = text(response.body, "pairId");
                    if (!id.matches("[A-Za-z0-9_-]{16,64}")) throw new Failure(Error.BAD_RESPONSE);
                    long expiry = expiry(response.body, PAIR_TTL);
                    if (!(BOT + "pair_" + id).equals(text(response.body, "botUrl"))) throw new Failure(Error.BAD_RESPONSE);
                    pairId = id; pairExpiresAt = expiry;
                } else if (operation == Operation.EXCHANGE) {
                    if (response.status == 202) {
                        if (!"pending".equals(text(response.body, "status"))
                                || expiry(response.body, PAIR_TTL) != pairExpiresAt) throw new Failure(Error.BAD_RESPONSE);
                    } else {
                        if (!"authenticated".equals(text(response.body, "status"))) throw new Failure(Error.BAD_RESPONSE);
                        String authenticatedToken = text(response.body, "token");
                        requireUser(response.body);
                        if (!authenticatedToken.matches("[A-Za-z0-9_-]{43}")) throw new Failure(Error.BAD_RESPONSE);
                        long expiry = expiry(response.body, SESSION_TTL);
                        if (BlackHolePrivateData.isAvailable()) {
                            try {
                                persistSession(ticket, new JSONObject()
                                        .put("telegramUserId", Long.toString(owner)).put("token", authenticatedToken)
                                        .put("expiresAtMs", expiry).toString());
                            } catch (Exception failure) { throw new Failure(Error.STORAGE); }
                        }
                        if (!current(ticket)) throw new Failure(Error.ACCOUNT_CHANGED);
                        token = authenticatedToken; tokenExpiresAt = expiry; clearPair(); freshSnapshot = false;
                        synchronized (LunagramSubscriptionClient.class) {
                            authRevision = ++AUTH_REVISIONS[account];
                        }
                        hasServerAnchor = false;
                        accessRevoked = false;
                        explicitDisconnect = false;
                    }
                } else if (operation == Operation.REVOKE) {
                    explicitDisconnect = true;
                    clearSession();
                } else {
                    Subscription parsed = parseSubscription(response.body);
                    if (subscription != null && (parsed.entitlementVersion < subscription.entitlementVersion
                            || parsed.serverNowMs < subscription.serverNowMs)) throw new Failure(Error.BAD_RESPONSE);
                    Quote parsedQuote = operation == Operation.PREVIEW ? parseQuote(response.body, parsed) : null;
                    if (operation == Operation.CONFIRM) validateActivation(response.body, parsed, code);
                    long receivedElapsed = SystemClock.elapsedRealtime();
                    persistSnapshot(ticket, parsed, receivedElapsed);
                    if (subscription != null && subscription.paidUntilMs > 0
                            && parsed.serverNowMs >= subscription.paidUntilMs) confirmedEndedAt = subscription.paidUntilMs;
                    subscription = parsed; quote = parsedQuote; freshSnapshot = true; hasServerAnchor = true;
                    confirmationSubmitted = false;
                    snapshotReceivedElapsed = receivedElapsed;
                }
                busy = false;
            }
        } catch (Failure failure) {
            fail(ticket, failure.error);
        } catch (java.io.IOException failure) {
            fail(ticket, Error.NETWORK);
        } catch (Exception failure) {
            fail(ticket, operation == Operation.RESTORE || operation == Operation.EXCHANGE ? Error.STORAGE : Error.BAD_RESPONSE);
        } finally {
            notifyChanged();
        }
    }
    private void fail(long ticket, Error failure) {
        synchronized (lock) {
            if (!current(ticket)) return;
            busy = false; error = failure; freshSnapshot = false;
            boolean ambiguousConfirmation = activeOperation == Operation.CONFIRM && confirmationSubmitted
                    && (failure == Error.NETWORK || failure == Error.SERVER || failure == Error.NOT_CONFIGURED
                    || failure == Error.STORAGE || failure == Error.BAD_RESPONSE);
            if (!ambiguousConfirmation && failure != Error.NETWORK && failure != Error.SERVER && failure != Error.NOT_CONFIGURED) quote = null;
            if (failure == Error.AUTH_REQUIRED || failure == Error.ACCOUNT_CHANGED) {
                try { clearSession(); } catch (Exception ignored) { token = null; }
                if (failure == Error.ACCOUNT_CHANGED) subscription = null;
            }
            if (failure == Error.EXPIRED || (failure != Error.NETWORK && pairId == null)) clearPair();
        }
    }
    private void restoreSession() throws Exception {
        if (!BlackHolePrivateData.isAvailable() || token != null) return;
        String stored = BlackHolePrivateData.read(account, SESSION_KEY, null);
        if (stored == null) return;
        if (stored.length() > 2048) throw new Failure(Error.STORAGE);
        JSONObject json = new JSONObject(stored);
        requireUser(json);
        String value = text(json, "token");
        if (!value.matches("[A-Za-z0-9_-]{43}")) throw new Failure(Error.STORAGE);
        long expiry = integer(json, "expiresAtMs");
        if (expiry <= System.currentTimeMillis()) { clearSession(); return; }
        if (expiry > System.currentTimeMillis() + SESSION_TTL + 30_000) throw new Failure(Error.STORAGE);
        token = value; tokenExpiresAt = expiry;
    }
    private void persistSession(long ticket, String value) throws Exception {
        synchronized (LunagramSubscriptionClient.class) {
            if (!current(ticket)) throw new Failure(Error.ACCOUNT_CHANGED);
            BlackHolePrivateData.write(account, owner, SESSION_KEY, value);
        }
    }
    private void clearSession() throws Exception {
        accessRevoked = true;
        token = null; tokenExpiresAt = 0; quote = null; freshSnapshot = false; clearPair();
        synchronized (LunagramSubscriptionClient.class) {
            if (currentOwner()) {
                // Invalidate every old in-flight client before deleting this session.
                authRevision = ++AUTH_REVISIONS[account];
                if (BlackHolePrivateData.isAvailable()) BlackHolePrivateData.remove(account, owner, SESSION_KEY);
            }
        }
    }
    private Subscription parseSubscription(JSONObject json) throws Exception {
        requireUser(json);
        require(integer(json, "schemaVersion") == 2 && json.get("featuresGated") instanceof Boolean
                && json.get("active") instanceof Boolean);
        long serverNow = boundedTime(json, "serverNowMs"), version = integer(json, "entitlementVersion");
        require(version >= 0 && version <= 9_007_199_254_740_991L && integer(json, "queueVersion") == version);
        boolean active = (Boolean) json.get("active");
        String plan = json.get("planId") == JSONObject.NULL ? null : knownPlan(json, "planId");
        long end = boundedTime(json, "expiresAtMs"), paidUntil = boundedTime(json, "paidUntilMs");
        Term current = json.get("current") == JSONObject.NULL ? null : parseTerm(json.get("current"));
        Object queueValue = json.get("queued");
        require(queueValue instanceof JSONArray);
        JSONArray array = (JSONArray) queueValue;
        require(array.length() <= 100);
        ArrayList<Term> queued = new ArrayList<>();
        ArrayList<String> orders = new ArrayList<>();
        long lastEnd = current == null ? 0 : current.expiresAtMs;
        if (current != null) orders.add(current.orderId);
        for (int i = 0; i < array.length(); i++) {
            Term term = parseTerm(array.get(i));
            require(term.startsAtMs > serverNow && term.startsAtMs >= lastEnd && !orders.contains(term.orderId));
            orders.add(term.orderId); queued.add(term); lastEnd = term.expiresAtMs;
        }
        boolean legacy = json.has("legacy");
        if (legacy) {
            require(Boolean.TRUE.equals(json.get("legacy")) && active && plan != null && current == null
                    && queued.isEmpty() && end > serverNow && paidUntil == end
                    && boundedTime(json, "legacyExpiresAtMs") == end);
        } else {
            require(!json.has("legacyExpiresAtMs") && active == (current != null));
            if (current == null) require(plan == null && end == 0);
            else require(current.startsAtMs <= serverNow && serverNow < current.expiresAtMs
                    && current.planId.equals(plan) && current.expiresAtMs == end);
            require(paidUntil == lastEnd);
        }
        return new Subscription(plan, end, active, serverNow, paidUntil, version, current, queued, legacy,
                (Boolean) json.get("featuresGated"));
    }
    private static Term parseTerm(Object value) throws Exception {
        require(value instanceof JSONObject);
        JSONObject json = (JSONObject) value;
        exactKeys(json, "orderId", "planId", "months", "startsAtMs", "expiresAtMs");
        String order = text(json, "orderId"), plan = knownPlan(json, "planId");
        require(order.matches("[a-f0-9]{48}"));
        int months = period(json);
        long start = boundedTime(json, "startsAtMs"), end = boundedTime(json, "expiresAtMs");
        require(start < end);
        return new Term(order, plan, months, start, end);
    }
    private Quote parseQuote(JSONObject body, Subscription status) throws Exception {
        require(body.get("quote") instanceof JSONObject && !status.legacy);
        JSONObject json = (JSONObject) body.get("quote");
        exactKeys(json, "quoteId", "planId", "months", "starsAmount", "startsAtMs", "expiresAtMs", "validUntilMs", "queueVersion");
        String id = text(json, "quoteId"), plan = knownPlan(json, "planId");
        require(id.matches("[a-f0-9]{48}"));
        int months = period(json);
        long stars = integer(json, "starsAmount"), start = boundedTime(json, "startsAtMs"), end = boundedTime(json, "expiresAtMs");
        long validUntil = boundedTime(json, "validUntilMs"), version = integer(json, "queueVersion");
        require(stars > 0 && stars <= Integer.MAX_VALUE && start >= status.serverNowMs && start < end
                && validUntil > status.serverNowMs && validUntil - status.serverNowMs <= 130_000
                && version == status.entitlementVersion);
        for (LunagramSubscriptionCatalog.Plan entry : LunagramSubscriptionCatalog.PLANS) {
            if (entry.id.equals(plan)) require(stars == entry.totalStars(LunagramSubscriptionCatalog.Period.fromMonths(months)));
        }
        // The queue may begin months after the quote expires; only the confirmation
        // deadline is short. Never reinterpret a queued start as an immediate upgrade.
        require(status.paidUntilMs == 0 || start >= status.paidUntilMs);
        return new Quote(id, plan, months, (int) stars, start, end, validUntil, version);
    }
    private void validateActivation(JSONObject body, Subscription status, String quoteId) throws Exception {
        Term activation = parseTerm(body.get("activation"));
        require(quote != null && quote.quoteId.equals(quoteId) && activation.planId.equals(quote.planId)
                && activation.months == quote.months && activation.startsAtMs == quote.startsAtMs
                && activation.expiresAtMs == quote.expiresAtMs && !status.legacy);
    }
    private static int period(JSONObject json) throws Exception {
        long value = integer(json, "months");
        require(value == 1 || value == 3 || value == 6 || value == 12);
        return (int) value;
    }
    private static String knownPlan(JSONObject json, String key) throws Exception {
        String value = text(json, key);
        for (LunagramSubscriptionCatalog.Plan entry : LunagramSubscriptionCatalog.PLANS) if (entry.id.equals(value)) return value;
        throw new Failure(Error.BAD_RESPONSE);
    }
    private static void require(boolean valid) throws Failure { if (!valid) throw new Failure(Error.BAD_RESPONSE); }
    private static void exactKeys(JSONObject json, String... expected) throws Exception {
        List<String> allowed = Arrays.asList(expected);
        Iterator<String> keys = json.keys(); int count = 0;
        while (keys.hasNext()) { require(allowed.contains(keys.next())); count++; }
        require(count == expected.length);
    }
    private static long boundedTime(JSONObject json, String name) throws Exception {
        long value = integer(json, name); require(value >= 0 && value <= MAX_TIME); return value;
    }
    private static JSONObject termJson(Term term) throws Exception {
        return new JSONObject().put("orderId", term.orderId).put("planId", term.planId).put("months", term.months)
                .put("startsAtMs", term.startsAtMs).put("expiresAtMs", term.expiresAtMs);
    }
    private void persistSnapshot(long ticket, Subscription state, long receivedElapsed) throws Exception {
        JSONArray queue = new JSONArray(); for (Term term : state.queued) queue.put(termJson(term));
        JSONObject json = new JSONObject().put("schemaVersion", 2).put("telegramUserId", Long.toString(owner))
                .put("serverNowMs", state.serverNowMs).put("entitlementVersion", state.entitlementVersion)
                .put("queueVersion", state.entitlementVersion).put("featuresGated", state.featuresGated).put("active", state.active)
                .put("leaseBoot", LunagramSubscriptionClock.bootId()).put("leaseElapsed", receivedElapsed)
                .put("planId", state.planId == null ? JSONObject.NULL : state.planId).put("expiresAtMs", state.expiresAtMs)
                .put("paidUntilMs", state.paidUntilMs).put("current", state.current == null ? JSONObject.NULL : termJson(state.current)).put("queued", queue);
        if (state.legacy) json.put("legacy", true).put("legacyExpiresAtMs", state.legacyExpiresAtMs);
        synchronized (LunagramSubscriptionClient.class) {
            if (!current(ticket)) throw new Failure(Error.ACCOUNT_CHANGED);
            requireCurrentObservation(state);
            if (BlackHolePrivateData.isAvailable()) {
                // Compare AND write under the same owner/epoch barrier. Comparing only
                // in the observer would let a late second client roll disk state back,
                // resurrecting a refunded term at the next process start.
                Subscription stored = readStoredSnapshot();
                requireNotOlder(state, stored);
                try { BlackHolePrivateData.write(account, owner, SNAPSHOT_KEY, json.toString()); }
                catch (Exception failure) { throw new Failure(Error.STORAGE); }
            }
            OBSERVATIONS[account] = new Observation(owner, accountEpoch, state);
        }
    }
    private void requireCurrentObservation(Subscription state) throws Failure {
        Observation previous = OBSERVATIONS[account];
        if (previous != null && previous.owner == owner && previous.epoch == accountEpoch)
            requireNotOlder(state, previous.state);
    }
    private static void requireNotOlder(Subscription state, Subscription previous) throws Failure {
        if (previous != null && (state.entitlementVersion < previous.entitlementVersion
                || state.serverNowMs < previous.serverNowMs)) throw new Failure(Error.BAD_RESPONSE);
    }
    private Subscription readStoredSnapshot() throws Exception {
        String stored;
        try { stored = BlackHolePrivateData.read(account, SNAPSHOT_KEY, null); }
        catch (Exception failure) { throw new Failure(Error.STORAGE); }
        if (stored == null) return null;
        if (stored.length() > BODY_LIMIT) throw new Failure(Error.STORAGE);
        return parseSubscription(new JSONObject(stored));
    }
    private void restoreSnapshot(long ticket) throws Exception {
        if (!BlackHolePrivateData.isAvailable() || subscription != null) return;
        synchronized (LunagramSubscriptionClient.class) {
            if (!current(ticket)) throw new Failure(Error.ACCOUNT_CHANGED);
            String stored = BlackHolePrivateData.read(account, SNAPSHOT_KEY, null);
            if (stored == null) return;
            if (stored.length() > BODY_LIMIT) throw new Failure(Error.STORAGE);
            JSONObject json = new JSONObject(stored);
            Subscription parsed = parseSubscription(json);
            requireCurrentObservation(parsed);
            subscription = parsed;
            OBSERVATIONS[account] = new Observation(owner, accountEpoch, parsed);
            freshSnapshot = false; hasServerAnchor = false;
            // Encrypted history alone is not authority. Restore only the same-boot monotonic anchor.
            if (json.has("leaseBoot") && json.has("leaseElapsed")) {
                long boot = integer(json, "leaseBoot"), elapsed = integer(json, "leaseElapsed");
                long now = SystemClock.elapsedRealtime();
                if (boot >= 0 && boot == LunagramSubscriptionClock.bootId() && elapsed >= 0
                        && now >= elapsed && now - elapsed < 24 * 60 * 60 * 1000L) {
                    snapshotReceivedElapsed = elapsed; hasServerAnchor = true;
                }
            }
        }
    }
    private void requireUser(JSONObject json) throws Exception {
        if (!Long.toString(owner).equals(text(json, "telegramUserId"))) throw new Failure(Error.ACCOUNT_CHANGED);
    }
    private static String text(JSONObject json, String name) throws Exception {
        Object value = json.get(name);
        if (!(value instanceof String)) throw new Failure(Error.BAD_RESPONSE);
        return (String) value;
    }
    private static long integer(JSONObject json, String name) throws Exception {
        Object value = json.get(name);
        if (!(value instanceof Long) && !(value instanceof Integer)) throw new Failure(Error.BAD_RESPONSE);
        return ((Number) value).longValue();
    }
    private static long expiry(JSONObject json, long ttl) throws Exception {
        long value = integer(json, "expiresAtMs"), now = System.currentTimeMillis();
        if (value <= now || value > now + ttl + 30_000) throw new Failure(Error.EXPIRED);
        return value;
    }
    private static Error serverError(Response response) {
        try {
            String code = text(response.body, "error");
            if ("INVALID_CODE".equals(code) || "CODE_ALREADY_USED".equals(code)
                    || "CODE_UNAVAILABLE".equals(code)) return Error.INVALID_CODE;
            if ("RATE_LIMITED".equals(code)) return Error.RATE_LIMITED;
            if ("NOT_CONFIGURED".equals(code) || "NOT_READY".equals(code)) return Error.NOT_CONFIGURED;
            if ("PAIR_EXPIRED".equals(code) || "PAIRING_NOT_AVAILABLE".equals(code)) return Error.EXPIRED;
            if ("UPGRADE_REQUIRED".equals(code)) return Error.UPGRADE_REQUIRED;
            if ("LEGACY_TERM_ACTIVE".equals(code)) return Error.LEGACY_TERM_ACTIVE;
            if ("QUOTE_EXPIRED".equals(code)) return Error.QUOTE_EXPIRED;
            if ("QUOTE_STALE".equals(code)) return Error.QUOTE_STALE;
            if ("QUEUE_FULL".equals(code)) return Error.QUEUE_FULL;
        } catch (Exception ignored) {}
        return Error.SERVER;
    }
    static String encode(byte[] value) { return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
    static String challenge(byte[] verifier) throws Exception { return encode(MessageDigest.getInstance("SHA-256").digest(verifier)); }
    private void notifyChanged() {
        final long ticket;
        synchronized (lock) { ticket = generation; }
        AndroidUtilities.runOnUIThread(() -> {
            Listener callback;
            AccessSnapshot snapshot;
            synchronized (lock) {
                boolean valid = current(ticket);
                callback = valid ? listener : null;
                snapshot = valid ? new AccessSnapshot(account, owner, accountEpoch,
                        hasServerAnchor && isAuthenticated() && !accessRevoked ? subscription : null,
                        hasServerAnchor ? subscription : null,
                        snapshotReceivedElapsed, accessRevoked, explicitDisconnect, confirmedEndedAt) : null;
            }
            StateObserver observer = stateObserver;
            if (snapshot != null && observer != null) observer.onState(this, snapshot);
            if (callback != null) callback.onChanged();
        });
    }
    private static final class Failure extends Exception {
        final Error error;
        Failure(Error error) { this.error = error; }
    }
    static byte[] readLimited(InputStream stream) throws Exception {
        return readLimited(stream, Long.MAX_VALUE);
    }
    static byte[] readLimited(InputStream stream, long deadlineNanos) throws Exception {
        if (stream == null) throw new Failure(Error.BAD_RESPONSE);
        try (InputStream input = stream; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while (true) {
                requireDeadline(deadlineNanos);
                count = input.read(buffer);
                requireDeadline(deadlineNanos);
                if (count == -1) break;
                if (result.size() + count > BODY_LIMIT) throw new Failure(Error.BAD_RESPONSE);
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }
    private static void requireDeadline(long deadlineNanos) throws java.io.IOException {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0) {
            throw new java.io.IOException("Billing request deadline exceeded");
        }
    }
    private static final class HttpsTransport implements Transport {
        private static final int REQUEST_TIMEOUT_MS = 15_000;
        private static final Timer WATCHDOG = new Timer("lunagram-billing-deadline", true);
        private volatile HttpsURLConnection active;
        @Override public void cancel() { HttpsURLConnection connection = active; if (connection != null) connection.disconnect(); }
        @Override public Response post(String path, String token, JSONObject body) throws Exception {
            HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(ORIGIN + path).openConnection();
            active = connection;
            long deadline = System.nanoTime() + REQUEST_TIMEOUT_MS * 1_000_000L;
            TimerTask timeout = new TimerTask() {
                @Override public void run() { connection.disconnect(); }
            };
            WATCHDOG.schedule(timeout, REQUEST_TIMEOUT_MS);
            try {
                SSLContext tls = SSLContext.getInstance("TLSv1.2"); tls.init(null, null, null);
                connection.setSSLSocketFactory(tls.getSocketFactory());
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
                connection.setUseCaches(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                if (payload.length > BODY_LIMIT) throw new Failure(Error.BAD_RESPONSE);
                requireDeadline(deadline);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
                int status = connection.getResponseCode();
                requireDeadline(deadline);
                // A 401 invalidates the credential even if its error body is absent/malformed.
                if (status == 401) return new Response(status, new JSONObject());
                if (status >= 300 && status < 400) throw new Failure(Error.BAD_RESPONSE);
                if (connection.getContentLength() > BODY_LIMIT) throw new Failure(Error.BAD_RESPONSE);
                byte[] bytes = readLimited(status >= 400 ? connection.getErrorStream() : connection.getInputStream(), deadline);
                try { return new Response(status, new JSONObject(new String(bytes, StandardCharsets.UTF_8))); }
                catch (Exception malformed) { throw new Failure(Error.BAD_RESPONSE); }
            } finally { timeout.cancel(); WATCHDOG.purge(); active = null; connection.disconnect(); }
        }
    }
}
