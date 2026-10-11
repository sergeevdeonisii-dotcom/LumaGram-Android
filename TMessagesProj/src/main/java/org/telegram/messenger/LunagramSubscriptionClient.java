package org.telegram.messenger;

import android.util.Base64;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/** Account-bound optional billing. This client never establishes Telegram feature access. */
public final class LunagramSubscriptionClient {
    private static final String ORIGIN = "https://lunagram-billing.pages.dev";
    private static final String BOT = "https://t.me/LunagramAppPayBot?start=";
    private static final String SESSION_KEY = "lunagram_subscription_session_v1_prod";
    private static final int BODY_LIMIT = 16 * 1024;
    private static final long PAIR_TTL = 10 * 60 * 1000L;
    private static final long SESSION_TTL = 30 * 24 * 60 * 60 * 1000L;
    private static final DispatchQueue QUEUE = new DispatchQueue("lunagram-billing");
    private static final long[] ACCOUNT_EPOCHS = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private static synchronized long accountEpoch(int account) { return ACCOUNT_EPOCHS[account]; }

    /** Called after UserConfig publishes an empty identity, before clearing its data.
     * The class monitor drains writes past their final owner check; never acquire
     * a client-instance lock here. Epochs also reject a late response after the
     * same Telegram user signs into the reused slot again. */
    public static synchronized void onAccountLogout(int account) { ACCOUNT_EPOCHS[account]++; }

    public enum Error { NONE, NETWORK, SERVER, BAD_RESPONSE, ACCOUNT_CHANGED, AUTH_REQUIRED,
        EXPIRED, STORAGE, INVALID_CODE, RATE_LIMITED, NOT_CONFIGURED }
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
    public static final class Subscription {
        public final String planId;
        public final long expiresAtMs;
        public final boolean active;
        Subscription(String planId, long expiresAtMs, boolean active) {
            this.planId = planId; this.expiresAtMs = expiresAtMs; this.active = active;
        }
    }
    private enum Operation { RESTORE, START, EXCHANGE, STATUS, REDEEM, REVOKE }
    private final Object lock = new Object();
    private final int account;
    private final long owner;
    private final long accountEpoch;
    private final Transport transport;
    private Listener listener;
    private long generation;
    private boolean disposed, busy;
    private String token, pairId;
    private long tokenExpiresAt, pairExpiresAt;
    private long lastExchangeNanos;
    private byte[] verifier;
    private Subscription subscription;
    private Error error = Error.NONE;

    public LunagramSubscriptionClient(int account, Listener listener) {
        this(account, new HttpsTransport());
        this.listener = listener;
    }
    LunagramSubscriptionClient(int account, Transport transport) {
        this.account = account;
        this.accountEpoch = accountEpoch(account);
        this.owner = UserConfig.getInstance(account).getClientUserId();
        this.transport = transport;
    }
    public boolean isBusy() { synchronized (lock) { return busy; } }
    public boolean isAuthenticated() { synchronized (lock) { return token != null && tokenExpiresAt > System.currentTimeMillis() && currentOwner(); } }
    public boolean isPairing() { synchronized (lock) { return pairId != null && pairExpiresAt > System.currentTimeMillis() && currentOwner(); } }
    public long pairExpiresAtMs() { synchronized (lock) { return pairExpiresAt; } }
    public Error error() { synchronized (lock) { return error; } }
    public Subscription subscription() { synchronized (lock) { return currentOwner() ? subscription : null; } }
    public boolean isPersistentStorageAvailable() { return BlackHolePrivateData.isAvailable(); }
    public boolean isAccountCurrent() { synchronized (lock) { return currentOwner(); } }
    public boolean isTestAccount() { return ConnectionsManager.getInstance(account).isTestBackend(); }
    public String authenticationUrl() {
        synchronized (lock) { return isPairing() ? BOT + "pair_" + pairId : null; }
    }
    public static String purchaseUrl(LunagramSubscriptionCatalog.Plan plan) {
        return plan == null ? null : BOT + "buy_" + plan.id;
    }
    public void restore() { submit(Operation.RESTORE, null); }
    public void startAuthentication() { submit(Operation.START, null); }
    public void checkAuthentication() { submit(Operation.EXCHANGE, null); }
    public void refresh() { submit(Operation.STATUS, null); }
    public void redeem(CharSequence input) {
        String code = LunagramSubscriptionCatalog.normalizeActivationCode(input);
        if (code == null) { synchronized (lock) { error = Error.INVALID_CODE; } notifyChanged(); return; }
        submit(Operation.REDEEM, code);
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
            disposed = true; clearPair(); token = null; subscription = null; listener = null;
        }
    }
    private boolean currentOwner() {
        return accountEpoch == accountEpoch(account) && owner > 0 && UserConfig.selectedAccount == account
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
            if (!currentOwner()) { clearPair(); token = null; subscription = null; error = Error.ACCOUNT_CHANGED; notifyChanged(); return; }
            if (operation == Operation.EXCHANGE) {
                long now = System.nanoTime();
                if (lastExchangeNanos != 0 && now - lastExchangeNanos < 1_000_000_000L) return;
                lastExchangeNanos = now;
            }
            busy = true; error = Error.NONE; ticket = ++generation;
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
                    restoreSession(); busy = false; notifyChanged(); return;
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
                    path = operation == Operation.REDEEM ? "/v1/redeem"
                            : operation == Operation.REVOKE ? "/v1/auth/revoke" : "/v1/subscription";
                    if (operation == Operation.REDEEM) body.put("code", code);
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
                        token = authenticatedToken; tokenExpiresAt = expiry; clearPair(); subscription = null;
                    }
                } else if (operation == Operation.REVOKE) {
                    clearSession();
                } else {
                    subscription = parseSubscription(response.body);
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
            busy = false; error = failure;
            if (failure == Error.AUTH_REQUIRED || failure == Error.ACCOUNT_CHANGED) {
                try { clearSession(); } catch (Exception ignored) { token = null; subscription = null; }
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
        token = null; tokenExpiresAt = 0; subscription = null; clearPair();
        synchronized (LunagramSubscriptionClient.class) {
            if (BlackHolePrivateData.isAvailable() && currentOwner()) BlackHolePrivateData.remove(account, owner, SESSION_KEY);
        }
    }
    private Subscription parseSubscription(JSONObject json) throws Exception {
        requireUser(json);
        if (!(json.get("featuresGated") instanceof Boolean) || !Boolean.FALSE.equals(json.get("featuresGated"))
                || !(json.get("active") instanceof Boolean)) throw new Failure(Error.BAD_RESPONSE);
        boolean active = (Boolean) json.get("active");
        Object planValue = json.get("planId"), expiryValue = json.get("expiresAtMs");
        String plan = planValue == JSONObject.NULL ? null : text(json, "planId");
        long expiry = expiryValue == JSONObject.NULL ? 0 : integer(json, "expiresAtMs");
        boolean known = plan == null;
        for (LunagramSubscriptionCatalog.Plan entry : LunagramSubscriptionCatalog.PLANS) known |= entry.id.equals(plan);
        if (!known || expiry < 0 || (active && (plan == null || expiry <= System.currentTimeMillis()))) throw new Failure(Error.BAD_RESPONSE);
        return new Subscription(plan, expiry, active);
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
        } catch (Exception ignored) {}
        return Error.SERVER;
    }
    static String encode(byte[] value) { return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
    static String challenge(byte[] verifier) throws Exception { return encode(MessageDigest.getInstance("SHA-256").digest(verifier)); }
    private void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            Listener callback;
            synchronized (lock) { callback = disposed ? null : listener; }
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
