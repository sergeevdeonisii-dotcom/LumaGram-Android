package org.telegram.messenger;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;

public final class LunagramSubscriptionClientTest {
    private static int checks;
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PAIR = "abcdefghijklmnopqrstuv";
    private static final class Transport implements LunagramSubscriptionClient.Transport {
        int calls, cancels;
        String path, token;
        JSONObject body;
        LunagramSubscriptionClient.Response next;
        Runnable beforeReturn;
        boolean network;
        public LunagramSubscriptionClient.Response post(String path,String token,JSONObject body) throws Exception {
            calls++; this.path = path; this.token = token; this.body = body;
            if (beforeReturn != null) { Runnable hook = beforeReturn; beforeReturn = null; hook.run(); }
            if (network) throw new java.io.IOException("Fixture disconnected");
            return next;
        }
        public void cancel() { cancels++; }
    }
    private static void check(boolean condition,String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static JSONObject authenticated(String owner) {
        return new JSONObject().put("status","authenticated").put("token",TOKEN).put("telegramUserId",owner)
                .put("expiresAtMs",System.currentTimeMillis()+86_400_000L);
    }
    private static JSONObject subscription(boolean active) {
        return new JSONObject().put("telegramUserId","101").put("planId",active ? "based" : null)
                .put("expiresAtMs",active ? System.currentTimeMillis()+86_400_000L : null)
                .put("active",active).put("featuresGated",false);
    }
    private static LunagramSubscriptionClient started(Transport transport) {
        LunagramSubscriptionClient client = new LunagramSubscriptionClient(0,transport);
        transport.next = new LunagramSubscriptionClient.Response(200,new JSONObject().put("pairId",PAIR)
                .put("botUrl","https://t.me/LunagramAppPayBot?start=pair_"+PAIR).put("expiresAtMs",System.currentTimeMillis()+600_000L));
        client.startAuthentication(); DispatchQueue.drain();
        check(client.isPairing() && !client.isBusy(),"Pair started without granting session");
        check("/v1/auth/start".equals(transport.path) && transport.token == null,"Start has no bearer header");
        check("101".equals(transport.body.get("expectedUserId")),"Current account is only an expected-user hint");
        return client;
    }
    private static LunagramSubscriptionClient connected(Transport transport) {
        LunagramSubscriptionClient client = started(transport);
        String challenge = (String)transport.body.get("challenge");
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101"));
        client.checkAuthentication(); DispatchQueue.drain();
        byte[] verifier = ((String)transport.body.get("verifier")).getBytes(StandardCharsets.US_ASCII);
        try { check(verifier.length == 43 && challenge.equals(LunagramSubscriptionClient.challenge(verifier)),"Actual exchange verifier matches start S256 challenge"); }
        catch(Exception error) { throw new AssertionError(error); }
        check(client.isAuthenticated() && !client.isPairing(),"Verified exchange ends polling and connects exact account");
        check(transport.token == null && "/v1/auth/exchange".equals(transport.path),"Exchange never exposes a token or verifier through a URL");
        return client;
    }
    public static void main(String[] args) throws Exception {
        check("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(LunagramSubscriptionClient.challenge(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".getBytes(StandardCharsets.US_ASCII))),"RFC7636 S256 vector");
        for (LunagramSubscriptionCatalog.Plan plan : LunagramSubscriptionCatalog.PLANS) {
            check(LunagramSubscriptionClient.purchaseUrl(plan).equals("https://t.me/LunagramAppPayBot?start=buy_"+plan.id),"Purchase links use fixed trusted bot/catalog plan");
        }
        BlackHolePrivateData.reset(); Transport transport = new Transport(); LunagramSubscriptionClient client = connected(transport);
        check(BlackHolePrivateData.writes == 1,"Session uses private-storage interface after verified exchange");
        transport.next = new LunagramSubscriptionClient.Response(200,subscription(true));
        client.refresh(); DispatchQueue.drain();
        check("/v1/subscription".equals(transport.path) && TOKEN.equals(transport.token),"Status uses authenticated fixed endpoint");
        check(client.subscription().active && "based".equals(client.subscription().planId),"Only server status establishes optional active subscription");
        client.redeem("abcd-1234-efgh-5678"); DispatchQueue.drain();
        check("/v1/redeem".equals(transport.path) && "ABCD1234EFGH5678".equals(transport.body.get("code")),"Redeem sends normalized code with bearer");
        int previous = transport.calls; client.redeem("short"); DispatchQueue.drain();
        check(transport.calls == previous && client.error() == LunagramSubscriptionClient.Error.INVALID_CODE,"Malformed codes never reach server");
        transport.next = new LunagramSubscriptionClient.Response(200,subscription(true).put("featuresGated",true));
        client.refresh(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.BAD_RESPONSE,"Feature-gating server response is rejected");
        transport.next = new LunagramSubscriptionClient.Response(200,subscription(false)); client.refresh(); DispatchQueue.drain();
        check(!client.subscription().active && client.subscription().planId == null,"Nullable inactive entitlement is supported");
        LunagramSubscriptionClient restored = new LunagramSubscriptionClient(0,new Transport()); restored.restore(); DispatchQueue.drain();
        check(restored.isAuthenticated() && restored.subscription() == null,"Restored session does not invent a cached paid entitlement");
        transport.next = new LunagramSubscriptionClient.Response(401,new JSONObject().put("error","UNAUTHENTICATED"));
        client.refresh(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.stored.isEmpty() && client.error() == LunagramSubscriptionClient.Error.AUTH_REQUIRED,"401 clears local session and requires reconnect");

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        transport.network = true; client.disconnect(); DispatchQueue.drain();
        check(client.isAuthenticated() && client.error() == LunagramSubscriptionClient.Error.NETWORK,"Failed revoke remains retryable, never claims server logout");
        transport.network = false; transport.next = new LunagramSubscriptionClient.Response(200,new JSONObject().put("ok",true));
        client.disconnect(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.stored.isEmpty(),"Successful revoke drops persistent session");

        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("202")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 0 && !client.isPairing(),"Wrong verified Telegram user never saves or accepts session");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        LunagramSubscriptionClient cancelled = client; transport.beforeReturn = cancelled::pause;
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 0 && !client.isBusy(),"Cancelled exchange cannot persist late credentials");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.beforeReturn = () -> UserConfig.selectedAccount = 1;
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 0,"Account switch rejects late exchange result"); client.dispose();

        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        BlackHolePrivateData.fail = true; transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && client.error() == LunagramSubscriptionClient.Error.STORAGE,"Keystore failure has no plaintext fallback");
        BlackHolePrivateData.reset(); BlackHolePrivateData.available = false; transport = new Transport(); client = connected(transport);
        check(BlackHolePrivateData.writes == 0 && !client.isPersistentStorageAvailable(),"Unsupported secure storage uses explicit memory-only session");
        client.dispose(); check(!client.isAuthenticated(),"Ephemeral session is dropped on fragment disposal");
        BlackHolePrivateData.reset(); transport = new Transport(); client = new LunagramSubscriptionClient(0,transport);
        transport.next = new LunagramSubscriptionClient.Response(200,new JSONObject().put("pairId",PAIR).put("botUrl","https://evil.example/?start=pair_"+PAIR).put("expiresAtMs",System.currentTimeMillis()+600_000L));
        client.startAuthentication(); DispatchQueue.drain();
        check(!client.isPairing() && client.error() == LunagramSubscriptionClient.Error.BAD_RESPONSE,"Backend cannot substitute an untrusted authorization link");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.next = new LunagramSubscriptionClient.Response(202,new JSONObject().put("status","pending").put("expiresAtMs",client.pairExpiresAtMs()));
        client.checkAuthentication(); DispatchQueue.drain(); previous = transport.calls; client.checkAuthentication(); DispatchQueue.drain();
        check(client.isPairing() && transport.calls == previous,"Pending exchange is sequential and throttled to at most one per second");
        client.cancelAuthentication(); check(!client.isPairing(),"Explicit cancel drops pairing verifier");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.next = new LunagramSubscriptionClient.Response(400,new JSONObject().put("error","PAIRING_NOT_AVAILABLE"));
        client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isPairing() && client.error() == LunagramSubscriptionClient.Error.EXPIRED,"Backend expired/unavailable pairing code stops retries and clears verifier");
        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        for (String code : new String[]{"CODE_ALREADY_USED","CODE_UNAVAILABLE"}) {
            transport.next = new LunagramSubscriptionClient.Response(400,new JSONObject().put("error",code));
            client.redeem("ABCD1234EFGH5678"); DispatchQueue.drain();
            check(client.error() == LunagramSubscriptionClient.Error.INVALID_CODE,"Backend redemption failures are actionable code errors");
        }
        transport.next = new LunagramSubscriptionClient.Response(503,new JSONObject().put("error","NOT_READY"));
        client.refresh(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.NOT_CONFIGURED,"Unavailable merchant/auth service has an honest configuration error");
        check(LunagramSubscriptionClient.readLimited(new ByteArrayInputStream(new byte[16*1024])).length == 16*1024,"16KiB response limit accepts exact boundary");
        boolean rejected = false;
        try { LunagramSubscriptionClient.readLimited(new ByteArrayInputStream(new byte[16*1024+1])); } catch(Exception expected) { rejected = true; }
        check(rejected,"Response stream is bounded even without Content-Length");
        rejected = false;
        try { LunagramSubscriptionClient.readLimited(new ByteArrayInputStream(new byte[1]),System.nanoTime()-1); }
        catch(java.io.IOException expected) { rejected = true; }
        check(rejected,"Total monotonic deadline cannot be extended by a slow response stream");
        BlackHolePrivateData.reset(); transport = new Transport(); client = new LunagramSubscriptionClient(0,transport);
        org.telegram.tgnet.ConnectionsManager.test = true;
        client.startAuthentication(); DispatchQueue.drain();
        check(transport.calls == 0 && !client.isAuthenticated() && !client.isAccountCurrent(),"TestDC IDs cannot authorize or purchase production subscriptions");
        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        org.telegram.tgnet.ConnectionsManager.test = true; client.refresh(); DispatchQueue.drain();
        check(!client.isAuthenticated() && client.subscription() == null,"Test backend switch clears memory session and hides old entitlement");
        System.out.println("PASS: "+checks+" billing-client contracts with fake transport/storage; no network, purchases, Android Keystore runtime, or JSON parser proof.");
    }
}
