package org.telegram.messenger;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLStreamHandler;
import java.net.URLConnection;
import javax.net.ssl.HttpsURLConnection;

public final class LunagramSubscriptionClientTest {
    private static int checks;
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String PAIR = "abcdefghijklmnopqrstuv";
    private static final String ORDER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String QUOTE = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
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
    private static final class LocalHttpsConnection extends HttpsURLConnection {
        boolean disconnected;
        LocalHttpsConnection(URL url) { super(url); }
        public void connect() { }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public String getCipherSuite() { return "fixture"; }
        public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        public java.security.cert.Certificate[] getServerCertificates() { return null; }
        public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
        public int getResponseCode() { return 401; }
    }
    private static void httpsCapabilityContracts() throws Exception {
        final LocalHttpsConnection[] captured = new LocalHttpsConnection[1];
        // Exercise the production transport without opening a socket, sending credentials, or
        // relying on the fixture JSON parser. A 401 has an intentionally body-independent path.
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
                return captured[0] = new LocalHttpsConnection(url);
            }
        } : null);
        java.lang.reflect.Constructor<?> constructor = Class.forName(
                "org.telegram.messenger.LunagramSubscriptionClient$HttpsTransport").getDeclaredConstructor();
        constructor.setAccessible(true);
        LunagramSubscriptionClient.Transport realTransport = (LunagramSubscriptionClient.Transport) constructor.newInstance();
        for (String path : new String[]{"/v1/auth/start", "/v1/auth/exchange", "/v1/auth/revoke",
                "/v2/subscription", "/v2/redeem/preview", "/v2/redeem/confirm",
                "/v3/crypto/current", "/v3/crypto/create", "/v3/crypto/order", "/v2/crypto/preview"}) {
            String bearer = path.equals("/v1/auth/start") || path.equals("/v1/auth/exchange") ? null : TOKEN;
            LunagramSubscriptionClient.Response response = realTransport.post(path, bearer, new JSONObject());
            check("ultra-20261013".equals(captured[0].getRequestProperty("X-Lunagram-Billing-Catalog")),
                    "Actual HTTPS transport declares the exact catalog capability: " + path);
            check(bearer == null ? captured[0].getRequestProperty("Authorization") == null
                            : ("Bearer " + TOKEN).equals(captured[0].getRequestProperty("Authorization")),
                    "Catalog capability does not replace bearer authentication: " + path);
            check("lunagram-billing.pages.dev".equals(captured[0].getURL().getHost())
                            && !captured[0].getInstanceFollowRedirects() && captured[0].disconnected && response.status == 401,
                    "Capability stays on fixed HTTPS origin; 401 and redirect safety unchanged: " + path);
        }
    }
    private static JSONObject authenticated(String owner) {
        return new JSONObject().put("status","authenticated").put("token",TOKEN).put("telegramUserId",owner)
                .put("expiresAtMs",System.currentTimeMillis()+86_400_000L);
    }
    private static JSONObject subscription(boolean active) {
        long now = System.currentTimeMillis();
        return new JSONObject().put("schemaVersion",2).put("telegramUserId","101").put("planId",active ? "based" : null)
                .put("expiresAtMs",active ? now+86_400_000L : 0L).put("serverNowMs",now)
                .put("entitlementVersion",1).put("queueVersion",1).put("paidUntilMs",active ? now+86_400_000L : 0L)
                .put("current",active ? term(ORDER,"based",1,now-1000,now+86_400_000L) : null)
                .put("queued",new JSONArray()).put("active",active).put("featuresGated",false);
    }
    private static JSONObject term(String id,String plan,int months,long start,long end) {
        return new JSONObject().put("orderId",id).put("planId",plan).put("months",months).put("startsAtMs",start).put("expiresAtMs",end);
    }
    private static JSONObject preview() {
        JSONObject status = subscription(false);
        long now = ((Number)status.get("serverNowMs")).longValue();
        return status.put("quote", new JSONObject().put("quoteId",QUOTE).put("planId","based").put("months",3)
                .put("starsAmount",100).put("startsAtMs",now).put("expiresAtMs",now+90L*86_400_000)
                .put("validUntilMs",now+120_000).put("queueVersion",1));
    }
    private static JSONObject confirmed(LunagramSubscriptionClient.Quote quote) {
        return subscription(false).put("entitlementVersion",2).put("queueVersion",2)
                .put("planId","ultra").put("active",true).put("expiresAtMs",quote.expiresAtMs)
                .put("paidUntilMs",quote.expiresAtMs)
                .put("current",term(ORDER,"ultra",quote.months,quote.startsAtMs,quote.expiresAtMs))
                .put("activation",term(ORDER,"ultra",quote.months,quote.startsAtMs,quote.expiresAtMs));
    }
    private static LunagramSubscriptionClient started(Transport transport) {
        LunagramSubscriptionClient client = new LunagramSubscriptionClient(0,transport);
        transport.next = new LunagramSubscriptionClient.Response(200,new JSONObject().put("pairId",PAIR)
                .put("botUrl","https://t.me/LunagramAppPayBot?start=pair_"+PAIR).put("expiresAtMs",System.currentTimeMillis()+600_000L));
        client.startAuthentication(); DispatchQueue.drain();
        check(client.isPairing() && !client.isBusy(),"Pair started without granting session");
        check("/v1/auth/start".equals(transport.path) && transport.token == null,"Start has no bearer header");
        check(Long.toString(UserConfig.ids[0]).equals(transport.body.get("expectedUserId")),"Current account is only an expected-user hint");
        return client;
    }
    private static LunagramSubscriptionClient connected(Transport transport) {
        LunagramSubscriptionClient client = started(transport);
        String challenge = (String)transport.body.get("challenge");
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated(Long.toString(UserConfig.ids[0])));
        client.checkAuthentication(); DispatchQueue.drain();
        byte[] verifier = ((String)transport.body.get("verifier")).getBytes(StandardCharsets.US_ASCII);
        try { check(verifier.length == 43 && challenge.equals(LunagramSubscriptionClient.challenge(verifier)),"Actual exchange verifier matches start S256 challenge"); }
        catch(Exception error) { throw new AssertionError(error); }
        check(client.isAuthenticated() && !client.isPairing(),"Verified exchange ends polling and connects exact account");
        check(transport.token == null && "/v1/auth/exchange".equals(transport.path),"Exchange never exposes a token or verifier through a URL");
        return client;
    }
    private static JSONObject cryptoOrder(String status) {
        long now = System.currentTimeMillis();
        return new JSONObject().put("orderId",ORDER).put("planId","ultra").put("months",3).put("usdCents",623)
                .put("status",status).put("createdAtMs",now-1000).put("expiresAtMs",now+900_000)
                .put("paidAtMs","paid".equals(status) ? now-100 : null)
                .put("checkoutUrl","pending".equals(status) ? "https://t.me/CryptoBot?start=IVAb12Cd34Ef" : null)
                .put("activated",false);
    }
    private static JSONObject cryptoPreview() {
        JSONObject state=preview();
        ((JSONObject)state.get("quote")).put("planId","ultra").put("starsAmount",450);
        return state;
    }
    private static void pairingRecoveryContracts() throws Exception {
        final String pairKey="101:lunagram_subscription_pair_v1_prod";
        final String sessionKey="101:lunagram_subscription_session_v1_prod";
        BlackHolePrivateData.reset();Transport transport=new Transport();LunagramSubscriptionClient first=started(transport);
        String originalChallenge=(String)transport.body.get("challenge"), originalUrl=first.authenticationUrl();
        long originalExpiry=first.pairExpiresAtMs();
        check(BlackHolePrivateData.stored.containsKey(pairKey),"Pending PKCE recovery uses account-private encrypted storage role");
        first.dispose();
        Transport restoredTransport=new Transport();LunagramSubscriptionClient restored=new LunagramSubscriptionClient(0,restoredTransport);
        restored.restore();DispatchQueue.drain();
        check(restored.isPairing()&&!restored.isAuthenticated()&&originalUrl.equals(restored.authenticationUrl())
                &&restored.pairExpiresAtMs()==originalExpiry&&restoredTransport.calls==0,"Recreated settings restores same unexpired pairing without granting auth or creating another request");
        restoredTransport.next=new LunagramSubscriptionClient.Response(200,authenticated("101"));
        restored.checkAuthentication();DispatchQueue.drain();
        String restoredVerifier=(String)restoredTransport.body.get("verifier");
        check(originalChallenge.equals(LunagramSubscriptionClient.challenge(restoredVerifier.getBytes(StandardCharsets.US_ASCII)))
                &&restored.isAuthenticated()&&!restored.isPairing(),"Approval obtained while screen was absent exchanges with original PKCE verifier");
        check(!BlackHolePrivateData.stored.containsKey(pairKey)&&BlackHolePrivateData.stored.containsKey(sessionKey),"Completed recovery retains only session, not pending verifier");

        BlackHolePrivateData.reset();transport=new Transport();first=started(transport);first.cancelAuthentication();first.dispose();
        restored=new LunagramSubscriptionClient(0,new Transport());restored.restore();DispatchQueue.drain();
        check(!restored.isPairing()&&!BlackHolePrivateData.stored.containsKey(pairKey),"Explicit cancel cannot resurrect pairing on next screen");
        BlackHolePrivateData.reset();transport=new Transport();first=started(transport);
        transport.next=new LunagramSubscriptionClient.Response(400,new JSONObject().put("error","PAIRING_NOT_AVAILABLE"));
        first.checkAuthentication();DispatchQueue.drain();first.dispose();
        restored=new LunagramSubscriptionClient(0,new Transport());restored.restore();DispatchQueue.drain();
        check(!restored.isPairing()&&!BlackHolePrivateData.stored.containsKey(pairKey),"Server expiry removes durable pairing to prevent a failed-approval loop");

        BlackHolePrivateData.reset();transport=new Transport();first=started(transport);first.dispose();
        JSONObject expiredPair=new JSONObject(BlackHolePrivateData.stored.get(pairKey)).put("expiresAtMs",System.currentTimeMillis()-1);
        BlackHolePrivateData.stored.put(pairKey,expiredPair.toString());
        restored=new LunagramSubscriptionClient(0,new Transport());restored.restore();DispatchQueue.drain();
        check(!restored.isPairing()&&restored.error()==LunagramSubscriptionClient.Error.NONE&&!BlackHolePrivateData.stored.containsKey(pairKey),"Expired stored pairing is discarded without restart loop or TTL extension");

        BlackHolePrivateData.reset();transport=new Transport();first=new LunagramSubscriptionClient(0,transport);
        final LunagramSubscriptionClient pausedStart=first;
        transport.next=new LunagramSubscriptionClient.Response(200,new JSONObject().put("pairId",PAIR)
                .put("botUrl","https://t.me/LunagramAppPayBot?start=pair_"+PAIR).put("expiresAtMs",System.currentTimeMillis()+600_000));
        transport.beforeReturn=pausedStart::pause;first.startAuthentication();DispatchQueue.drain();
        check(!first.isPairing()&&BlackHolePrivateData.stored.isEmpty(),"Paused START cannot save a late verifier/link to disk");

        BlackHolePrivateData.reset();transport=new Transport();first=started(transport);first.dispose();
        UserConfig.logout(0);UserConfig.ids[0]=101;
        restored=new LunagramSubscriptionClient(0,new Transport());restored.restore();DispatchQueue.drain();
        check(!restored.isPairing()&&BlackHolePrivateData.stored.isEmpty(),"Telegram logout clears pending pairing even for same-user slot reuse");

        BlackHolePrivateData.reset();transport=new Transport();first=started(transport);first.dispose();
        UserConfig.ids[0]=202;
        restored=new LunagramSubscriptionClient(0,new Transport());restored.restore();DispatchQueue.drain();
        check(!restored.isPairing()&&!restored.isAuthenticated(),"Another account cannot restore previous owner's verifier");

        BlackHolePrivateData.reset();transport=new Transport();first=started(transport);
        BlackHolePrivateData.stored.put(sessionKey,new JSONObject().put("telegramUserId","101").put("token",TOKEN)
                .put("expiresAtMs",System.currentTimeMillis()-1).toString());
        Transport passiveTransport=new Transport();LunagramSubscriptionClient passive=new LunagramSubscriptionClient(0,passiveTransport);
        passive.restore();DispatchQueue.drain();
        check(first.isAccountCurrent()&&first.isPairing()&&!BlackHolePrivateData.stored.containsKey(sessionKey)
                &&passiveTransport.calls==0,"Passive restore of an expired disk session does not invalidate an ongoing UI pairing or contact server");
        transport.next=new LunagramSubscriptionClient.Response(200,authenticated("101"));first.checkAuthentication();DispatchQueue.drain();
        check(first.isAuthenticated()&&!passive.isAccountCurrent(),"Real exchange still advances auth revision and invalidates passive old clients");
        int calls=passiveTransport.calls;passive.restore();DispatchQueue.drain();
        check(passiveTransport.calls==calls&&!passive.isAuthenticated(),"Invalidated passive client cannot silently reauthenticate or recover the new session");
        for(boolean explicitDisconnect:new boolean[]{true,false}) {
            BlackHolePrivateData.reset();transport=new Transport();first=connected(transport);
            LunagramSubscriptionClient secondPair=started(new Transport());
            check(BlackHolePrivateData.stored.containsKey(pairKey),"Separate client has a pending pairing before revocation");
            transport.next=new LunagramSubscriptionClient.Response(explicitDisconnect?200:401,
                    new JSONObject().put(explicitDisconnect?"revoked":"error",explicitDisconnect?true:"UNAUTHENTICATED"));
            if(explicitDisconnect)first.disconnect();else first.refresh();
            DispatchQueue.drain();
            restored=new LunagramSubscriptionClient(0,new Transport());restored.restore();DispatchQueue.drain();
            check(!restored.isPairing()&&!restored.isAuthenticated()&&!secondPair.isAccountCurrent()
                    &&!BlackHolePrivateData.stored.containsKey(pairKey)&&!BlackHolePrivateData.stored.containsKey(sessionKey),
                    "Revocation clears other pending owner pairings so a fresh fragment cannot reconnect: explicit="+explicitDisconnect);
        }
    }
    private static JSONObject cryptoState(JSONObject order) {
        return new JSONObject().put("schemaVersion",1).put("telegramUserId","101").put("serverNowMs",System.currentTimeMillis())
                .put("cryptoAvailable",true).put("termsVersion",LunagramSubscriptionClient.CRYPTO_TERMS_VERSION).put("order",order);
    }
    private static void cryptoContracts() throws Exception {
        check(LunagramSubscriptionClient.isCryptoCheckoutUrl("https://t.me/CryptoBot?start=IVAb12Cd34Ef"),"Observed official Crypto Bot invoice deep link is accepted");
        check(LunagramSubscriptionClient.isCryptoCheckoutUrl("https://t.me/CryptoBot?start=IV"+"a".repeat(62)),"Telegram bot payload upper bound accepts exactly64 characters");
        for (String url : new String[]{"http://t.me/CryptoBot?start=IVAb12Cd34Ef", "https://t.me.evil.test/CryptoBot?start=IVAb12Cd34Ef",
                "https://t.me:443/CryptoBot?start=IVAb12Cd34Ef", "https://a@t.me/CryptoBot?start=IVAb12Cd34Ef",
                "https://t.me/CryptoBot?start=IVAb12Cd34Ef&x=1", "https://t.me/CryptoBot?start=IVAb12Cd34Ef#hash",
                "https://t.me/CryptoBot/?start=IVAb12Cd34Ef", "https://t.me/CryptoBot?start=%49VAb12Cd34Ef",
                "https://t.me/CryptoTestnetBot?start=IVAb12Cd34Ef", "https://t.me/FakeBot?start=IVAb12Cd34Ef",
                "https://t.me/cryptobot?start=IVAb12Cd34Ef", "https://t.me/CryptoBot?start=IV", "https://t.me/CryptoBot?start=XXAb12Cd34Ef",
                "https://t.me/CryptoBot?start=IV"+"a".repeat(63), "https://t.me/CryptoBot?start=IVAb12Cd34Ef\n",
                "https://t.me/CryptoBot?start=IVАб12", "https://app.cr.bot/invoices/Ab12Cd34Ef56", "tg://resolve?domain=CryptoBot", null}) {
            check(!LunagramSubscriptionClient.isCryptoCheckoutUrl(url),"Unsafe checkout URL rejected");
        }
        BlackHolePrivateData.reset(); Transport transport = new Transport(); LunagramSubscriptionClient client = connected(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,cryptoState(null));
        client.refreshCrypto(); DispatchQueue.drain();
        check("/v3/crypto/current".equals(transport.path) && TOKEN.equals(transport.token) && !transport.body.keys().hasNext(),"Bot-checkout recovery uses versioned route, owner-bound private bearer and empty body");
        check(client.cryptoState() != null && client.cryptoState().available && client.subscription() == null,"Crypto readiness never creates an entitlement");
        transport.next = new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("pending")));
        client.createCryptoOrder(LunagramSubscriptionCatalog.Plan.ULTRA,LunagramSubscriptionCatalog.Period.MONTH_3); DispatchQueue.drain();
        check("/v3/crypto/create".equals(transport.path) && "ultra".equals(transport.body.get("planId")) && ((Number)transport.body.get("months")).intValue()==3,"Bot checkout uses sole public plan and fixed period");
        check(((String)transport.body.get("requestKey")).matches("[a-f0-9]{48}") && LunagramSubscriptionClient.CRYPTO_TERMS_VERSION.equals(transport.body.get("termsVersion")),"Create carries fresh opaque idempotency key and separate crypto consent version");
        check(!transport.body.has("usdCents") && !transport.body.has("telegramUserId") && !transport.body.has("checkoutUrl"),"Client never supplies price, owner or checkout destination");
        int calls = transport.calls;
        client.createCryptoOrder(LunagramSubscriptionCatalog.Plan.ULTRA,LunagramSubscriptionCatalog.Period.MONTH_12); DispatchQueue.drain();
        check(transport.calls==calls && client.error()==LunagramSubscriptionClient.Error.CHECKOUT_PENDING,"Outstanding checkout blocks another invoice");
        transport.next = new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("paid")));
        client.checkCryptoOrder(); DispatchQueue.drain();
        check("/v3/crypto/order".equals(transport.path) && ORDER.equals(transport.body.get("orderId")),"Bot payment check addresses only opaque order id");
        check("paid".equals(client.cryptoState().order.status) && client.subscription()==null,"Paid invoice alone does not activate feature access");
        transport.next = new LunagramSubscriptionClient.Response(200,cryptoPreview());
        client.previewCryptoOrder(); DispatchQueue.drain();
        check("/v2/crypto/preview".equals(transport.path) && ORDER.equals(transport.body.get("orderId")) && !transport.body.has("code"),"Paid owner order obtains date preview without revealing bearer key");
        check(client.quote()!=null && !client.subscription().active,"Paid preview is still not activation");
        transport.next = new LunagramSubscriptionClient.Response(200,confirmed(client.quote()));
        client.confirmCode(); DispatchQueue.drain();
        check(client.subscription().active && "/v2/redeem/confirm".equals(transport.path),"Explicit common v2 confirmation establishes server-observed entitlement");

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,cryptoState(null)); client.refreshCrypto(); DispatchQueue.drain();
        transport.network=true;
        client.createCryptoOrder(LunagramSubscriptionCatalog.Plan.ULTRA,LunagramSubscriptionCatalog.Period.MONTH_3); DispatchQueue.drain();
        String retryKey=(String)transport.body.get("requestKey");
        check(client.error()==LunagramSubscriptionClient.Error.NETWORK,"Unknown invoice create is not reported as paid or successful");
        calls=transport.calls;
        client.createCryptoOrder(LunagramSubscriptionCatalog.Plan.ULTRA,LunagramSubscriptionCatalog.Period.MONTH_3);DispatchQueue.drain();
        check(transport.calls==calls && client.isCryptoRecoveryRequired(),"Unknown create must recover current state before retrying any invoice");
        transport.network=false;transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(null));
        client.checkCryptoOrder();DispatchQueue.drain();
        check("/v3/crypto/current".equals(transport.path)&&!client.isCryptoRecoveryRequired(),"Check payment after unknown create recovers latest current order, never stale prior invoice");
        transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("pending")));
        client.createCryptoOrder(LunagramSubscriptionCatalog.Plan.ULTRA,LunagramSubscriptionCatalog.Period.MONTH_3);DispatchQueue.drain();
        check(retryKey.equals(transport.body.get("requestKey")),"Recovery without order preserves original idempotency key for explicit retry");
        Transport restoredTransport=new Transport();LunagramSubscriptionClient restored=new LunagramSubscriptionClient(0,restoredTransport);
        restored.restore();DispatchQueue.drain();
        restoredTransport.next=new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("paid")));
        restored.refreshCrypto();DispatchQueue.drain();
        check(restored.cryptoState()!=null && "paid".equals(restored.cryptoState().order.status),"Process recreation restores private session and recovers paid order from server");
        check(BlackHolePrivateData.writes==2,"Crypto metadata and checkout URLs are not persisted; only pairing recovery and authenticated session were written");

        for(int mutation=0;mutation<17;mutation++) {
            BlackHolePrivateData.reset();transport=new Transport();client=connected(transport);
            JSONObject order=cryptoOrder("pending"), state=cryptoState(order);
            switch(mutation) {
                case 0:state.put("telegramUserId","202");break;
                case 1:state.put("schemaVersion",2);break;
                case 2:state.put("token","secret");break;
                case 3:state.put("cryptoAvailable","true");break;
                case 4:state.put("termsVersion","stars-terms");break;
                case 5:order.put("usdCents",1);break;
                case 6:order.put("months",2);break;
                case 7:order.put("status","approved");break;
                case 8:order.put("activated",true);break;
                case 9:order.put("paidAtMs",System.currentTimeMillis());break;
                case 10:order.put("checkoutUrl","https://t.me/FakeBot?start=IVAb12Cd34Ef");break;
                case 11:order.put("expiresAtMs",1);break;
                case 12:order.put("orderId","not-opaque");break;
                case 13:order.put("status","paid").put("paidAtMs",null);break;
                case 14:order.put("apiToken","secret");break;
                case 15:state.put("cryptoAvailable",false);break;
                default:order.put("status","paid").put("checkoutUrl",null).put("paidAtMs",System.currentTimeMillis()+10_000)
                        .put("expiresAtMs",System.currentTimeMillis()+1000);
            }
            transport.next=new LunagramSubscriptionClient.Response(200,state);client.refreshCrypto();DispatchQueue.drain();
            check(client.cryptoState()==null && (client.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE || client.error()==LunagramSubscriptionClient.Error.ACCOUNT_CHANGED),"Strict crypto schema rejects malformed/cross-account reply "+mutation);
        }
        BlackHolePrivateData.reset();transport=new Transport();client=connected(transport);
        JSONObject skewedPaid=cryptoOrder("paid");
        skewedPaid.put("paidAtMs",((Number)skewedPaid.get("createdAtMs")).longValue()-31_000);
        transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(skewedPaid));client.refreshCrypto();DispatchQueue.drain();
        check(client.error()==LunagramSubscriptionClient.Error.NONE && "paid".equals(client.cryptoState().order.status),"Provider creation/pay clock allowance matches server's 31-second lower bound");
        BlackHolePrivateData.reset();transport=new Transport();client=connected(transport);
        final LunagramSubscriptionClient paused=client;
        transport.beforeReturn=paused::pause;transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("paid")));
        client.refreshCrypto();DispatchQueue.drain();
        check(client.cryptoState()==null && client.subscription()==null,"Late paid reply after pause cannot publish checkout or entitlement");
    }
    private static void singleUltraCompatibility() {
        for(LunagramSubscriptionCatalog.Plan plan:LunagramSubscriptionCatalog.Plan.values()) {
            if(plan==LunagramSubscriptionCatalog.Plan.ULTRA)continue;
            check(LunagramSubscriptionClient.purchaseUrl(plan)==null,"Retired plan cannot create a new bot-purchase link");
            for(LunagramSubscriptionCatalog.Period period:LunagramSubscriptionCatalog.PERIODS)
                check(LunagramSubscriptionClient.purchaseUrl(plan,period)==null,"Retired duration deep link not offered");
            BlackHolePrivateData.reset(); Transport transport=new Transport(); LunagramSubscriptionClient client=connected(transport);
            int calls=transport.calls;
            client.createCryptoOrder(plan,LunagramSubscriptionCatalog.Period.MONTH_1);DispatchQueue.drain();
            check(transport.calls==calls&&client.subscription()==null,"Retired direct API purchase has no transport or entitlement side effect");
            JSONObject historical=cryptoOrder("paid").put("planId",plan.id).put("usdCents",plan.totalUsdCents(LunagramSubscriptionCatalog.Period.MONTH_3));
            transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(historical));client.refreshCrypto();DispatchQueue.drain();
            check(client.error()==LunagramSubscriptionClient.Error.NONE&&plan.id.equals(client.cryptoState().order.planId),"Historical crypto order retained with original known plan and price");
        }
        for(int cents:new int[]{623,1250}) {
            BlackHolePrivateData.reset(); Transport transport=new Transport(); LunagramSubscriptionClient client=connected(transport);
            transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("paid").put("usdCents",cents)));
            client.refreshCrypto();DispatchQueue.drain();
            check(client.error()==LunagramSubscriptionClient.Error.NONE&&client.cryptoState().order.usdCents==cents,"Current and former Ultra receipt retains its exact authenticated price");
        }
        BlackHolePrivateData.reset(); Transport transport=new Transport(); LunagramSubscriptionClient client=connected(transport);
        transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(null));client.refreshCrypto();DispatchQueue.drain();
        transport.next=new LunagramSubscriptionClient.Response(200,cryptoState(cryptoOrder("pending").put("usdCents",1250)));
        client.createCryptoOrder(LunagramSubscriptionCatalog.Plan.ULTRA,LunagramSubscriptionCatalog.Period.MONTH_3);DispatchQueue.drain();
        check(client.error()==LunagramSubscriptionClient.Error.BAD_RESPONSE&&client.cryptoState().order==null,"New Ultra checkout cannot silently charge the old higher Ultra price");
    }
    public static void main(String[] args) throws Exception {
        singleUltraCompatibility();
        check("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(LunagramSubscriptionClient.challenge(
                "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".getBytes(StandardCharsets.US_ASCII))),"RFC7636 S256 vector");
        for (LunagramSubscriptionCatalog.Plan plan : LunagramSubscriptionCatalog.PLANS) {
            check(LunagramSubscriptionClient.purchaseUrl(plan).equals("https://t.me/LunagramAppPayBot?start=buy_"+plan.id),"Purchase links use fixed trusted bot/catalog plan");
            for (LunagramSubscriptionCatalog.Period period : LunagramSubscriptionCatalog.PERIODS) {
                check(LunagramSubscriptionClient.purchaseUrl(plan,period).equals("https://t.me/LunagramAppPayBot?start=buy_"+plan.id+"_"+period.months),"Duration deep links are exact catalog values");
            }
        }
        BlackHolePrivateData.reset(); Transport transport = new Transport(); LunagramSubscriptionClient client = connected(transport);
        check(BlackHolePrivateData.writes == 2 && !BlackHolePrivateData.stored.containsKey("101:lunagram_subscription_pair_v1_prod"),"Session uses private-storage interface after verified exchange and removes encrypted pending pairing");
        transport.next = new LunagramSubscriptionClient.Response(200,subscription(true));
        client.refresh(); DispatchQueue.drain();
        check("/v2/subscription".equals(transport.path) && TOKEN.equals(transport.token),"Status uses authenticated fixed v2 endpoint");
        check(client.subscription().active && "based".equals(client.subscription().planId),"Only server status establishes optional active subscription");
        check(client.subscriptionIsFresh() && client.estimatedServerNowMs() >= client.subscription().serverNowMs,"Fresh display clock uses server anchor");
        transport.next = new LunagramSubscriptionClient.Response(200,preview());
        client.previewCode("abcd-1234-efgh-5678"); DispatchQueue.drain();
        check("/v2/redeem/preview".equals(transport.path) && "ABCD1234EFGH5678".equals(transport.body.get("code")),"Preview sends normalized code without consuming it");
        check(client.quote() != null && client.quote().months == 3 && client.quote().starsAmount == 100,"Preview exposes exact server terms before confirmation");
        transport.next = new LunagramSubscriptionClient.Response(200,confirmed(client.quote()));
        client.confirmCode(); DispatchQueue.drain();
        check("/v2/redeem/confirm".equals(transport.path) && QUOTE.equals(transport.body.get("quoteId")) && !transport.body.has("code"),"Confirmation sends only opaque owner-bound quote");
        check(client.subscription().active && client.quote() == null,"Successful confirmation clears quote and installs observed state");
        int previous = transport.calls; client.previewCode("short"); DispatchQueue.drain();
        check(transport.calls == previous && client.error() == LunagramSubscriptionClient.Error.INVALID_CODE,"Malformed codes never reach server");
        transport.next = new LunagramSubscriptionClient.Response(200,subscription(true).put("featuresGated","true"));
        client.refresh(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.BAD_RESPONSE,"Non-boolean feature-gating response is rejected");
        transport.next = new LunagramSubscriptionClient.Response(200,subscription(false).put("entitlementVersion",3).put("queueVersion",3)); client.refresh(); DispatchQueue.drain();
        check(!client.subscription().active && client.subscription().planId == null,"Nullable inactive entitlement is supported");
        LunagramSubscriptionClient restored = new LunagramSubscriptionClient(0,new Transport()); restored.restore(); DispatchQueue.drain();
        check(restored.isAuthenticated() && restored.subscription() != null && !restored.subscriptionIsFresh(),"Restored snapshot is explicitly stale, never an offline entitlement");
        transport.next = new LunagramSubscriptionClient.Response(401,new JSONObject().put("error","UNAUTHENTICATED"));
        client.refresh(); DispatchQueue.drain();
        check(!client.isAuthenticated() && client.subscription() != null && !client.subscriptionIsFresh()
                && !BlackHolePrivateData.stored.containsKey("101:lunagram_subscription_session_v1_prod")
                && BlackHolePrivateData.stored.containsKey("101:lunagram_subscription_snapshot_v2_prod")
                && client.error() == LunagramSubscriptionClient.Error.AUTH_REQUIRED,"401 drops credential but preserves last confirmed paid dates for display");

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        transport.network = true; client.disconnect(); DispatchQueue.drain();
        check(client.isAuthenticated() && client.error() == LunagramSubscriptionClient.Error.NETWORK,"Failed revoke remains retryable, never claims server logout");
        transport.network = false; transport.next = new LunagramSubscriptionClient.Response(200,new JSONObject().put("ok",true));
        client.disconnect(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.stored.isEmpty(),"Successful revoke drops persistent session");

        for (String role : new String[]{"lunagram_subscription_snapshot_v2_prod", "lunagram_subscription_session_v1_prod", "lunagram_subscription_pair_v1_prod"}) {
            BlackHolePrivateData.reset(); transport = new Transport(); client = new LunagramSubscriptionClient(0,transport);
            BlackHolePrivateData.ioReadFailureRole = role;
            client.restore(); DispatchQueue.drain();
            check(client.error() == LunagramSubscriptionClient.Error.STORAGE && !client.isAuthenticated()
                    && !client.isBusy() && transport.calls == 0 && BlackHolePrivateData.writes == 0,
                    "Local secure-storage I/O restore failure is not presented as a network failure: " + role);
        }
        BlackHolePrivateData.reset(); transport = new Transport(); client = new LunagramSubscriptionClient(0,transport);
        transport.network = true; client.startAuthentication(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.NETWORK && !client.isPairing(),
                "Pair-start transport I/O failure remains a network error");
        transport.network = false;
        transport.next = new LunagramSubscriptionClient.Response(400,new JSONObject().put("error","INVALID_REQUEST"));
        client.startAuthentication(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.SERVER && !client.isPairing(),
                "An unrecognized server rejection is not classified as a connection failure");

        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("202")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 1 && !client.isPairing() && BlackHolePrivateData.stored.isEmpty(),"Wrong verified Telegram user never saves or accepts session and removes pending pairing");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        LunagramSubscriptionClient cancelled = client; transport.beforeReturn = cancelled::pause;
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 1 && !client.isBusy() && BlackHolePrivateData.stored.containsKey("101:lunagram_subscription_pair_v1_prod"),"Cancelled exchange cannot persist late credentials but retains encrypted recovery");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.beforeReturn = () -> UserConfig.selectedAccount = 1;
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101")); client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 1,"Account switch rejects late exchange result"); client.dispose();

        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.beforeReturn = () -> UserConfig.logout(0);
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101"));
        client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 1 && BlackHolePrivateData.stored.isEmpty(),
                "Logout before late exchange response cannot resurrect a persistent session");
        BlackHolePrivateData.reset(); transport = new Transport(); client = started(transport);
        transport.beforeReturn = () -> { UserConfig.logout(0); UserConfig.ids[0] = 101; };
        transport.next = new LunagramSubscriptionClient.Response(200,authenticated("101"));
        client.checkAuthentication(); DispatchQueue.drain();
        check(!client.isAuthenticated() && BlackHolePrivateData.writes == 1,
                "Logout epoch rejects late result even after the SAME user signs into the same slot");
        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        int oldCalls = transport.calls;
        UserConfig.logout(0); UserConfig.ids[0] = 202;
        Transport replacementTransport = new Transport();
        LunagramSubscriptionClient replacement = new LunagramSubscriptionClient(0,replacementTransport);
        replacement.restore(); DispatchQueue.drain();
        check(!replacement.isAuthenticated(),"Replacement account never restores outgoing user's billing token");
        replacement = connected(replacementTransport);
        check(replacement.isAuthenticated(),"Replacement account can authenticate independently");
        String replacementStored = BlackHolePrivateData.stored.get("202:lunagram_subscription_session_v1_prod");
        client.disconnect(); DispatchQueue.drain();
        check(transport.calls == oldCalls && replacementStored.equals(BlackHolePrivateData.stored.get("202:lunagram_subscription_session_v1_prod")),
                "Stale client's logout cannot revoke or delete replacement-account session");
        UserConfig.ids[0] = 101;
        LunagramSubscriptionClient previousOwner = new LunagramSubscriptionClient(0,new Transport());
        previousOwner.restore(); DispatchQueue.drain();
        check(!previousOwner.isAuthenticated(),"Returning old owner does not recover a session cleared by logout");

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
            client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
            check(client.error() == LunagramSubscriptionClient.Error.INVALID_CODE,"Backend redemption failures are actionable code errors");
        }
        transport.next = new LunagramSubscriptionClient.Response(503,new JSONObject().put("error","NOT_READY"));
        client.refresh(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.NOT_CONFIGURED,"Unavailable merchant/auth service has an honest configuration error");
        check(LunagramSubscriptionClient.readLimited(new ByteArrayInputStream(new byte[32*1024])).length == 32*1024,"32KiB v2 response limit accepts exact boundary");
        boolean rejected = false;
        try { LunagramSubscriptionClient.readLimited(new ByteArrayInputStream(new byte[32*1024+1])); } catch(Exception expected) { rejected = true; }
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

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        JSONObject initial = subscription(true);
        transport.next = new LunagramSubscriptionClient.Response(200,initial); client.refresh(); DispatchQueue.drain();
        long initialEnd = client.subscription().expiresAtMs;
        transport.network = true; client.refresh(); DispatchQueue.drain();
        check(client.subscription().expiresAtMs == initialEnd && !client.subscriptionIsFresh(),"Network failure preserves exact last confirmed end with stale state");
        transport.network = false;
        client.dispose();
        Transport cacheTransport = new Transport(); client = new LunagramSubscriptionClient(0,cacheTransport);
        client.restore(); DispatchQueue.drain();
        check(client.subscription().expiresAtMs == initialEnd && client.estimatedServerNowMs() == 0 && !client.subscriptionIsFresh(),"Restart never pretends cached wall clock is fresh server time");
        cacheTransport.next = new LunagramSubscriptionClient.Response(401,new JSONObject()); client.refresh(); DispatchQueue.drain();
        check(client.subscription().expiresAtMs == initialEnd && !client.isAuthenticated(),"Credential expiry is not paid-term expiry");
        UserConfig.logout(0); UserConfig.ids[0] = 101;
        restored = new LunagramSubscriptionClient(0,new Transport()); restored.restore(); DispatchQueue.drain();
        check(restored.subscription() == null,"Logout removes both token and cached paid dates even for same-user slot reuse");

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        LunagramSubscriptionClient cancelledPreview = client;
        transport.beforeReturn = cancelledPreview::clearQuote;
        transport.next = new LunagramSubscriptionClient.Response(200,preview()); client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
        check(client.quote() == null && client.subscription() == null && !client.isBusy(),"Closing code dialog discards late preview and does not change subscription");
        transport.next = new LunagramSubscriptionClient.Response(200,preview()); client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
        LunagramSubscriptionClient.Quote observed = client.quote();
        transport.network = true; client.confirmCode(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.NETWORK && client.quote() == observed,"Ambiguous confirmation keeps same quote for idempotent retry");
        android.os.SystemClock.offset = 180_000;
        transport.network = false; transport.next = new LunagramSubscriptionClient.Response(200,confirmed(observed));
        previous = transport.calls; client.confirmCode(); DispatchQueue.drain();
        check(transport.calls == previous+1 && QUOTE.equals(transport.body.get("quoteId")) && client.error() == LunagramSubscriptionClient.Error.NONE,
                "Lost confirmation can retry identical quote after deadline without creating another activation");
        check(client.subscription().expiresAtMs == observed.expiresAtMs,"Confirmation never locally adds or extends months");
        android.os.SystemClock.offset = 1_200_000;
        check(!client.subscriptionIsFresh(),"Freshness expires on monotonic clock, not an unbounded active flag");
        android.os.SystemClock.offset = 0;

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,preview()); client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
        android.os.SystemClock.offset = 180_000;
        previous = transport.calls; client.confirmCode(); DispatchQueue.drain();
        check(transport.calls == previous && client.error() == LunagramSubscriptionClient.Error.QUOTE_EXPIRED,"Never-confirmed expired quote cannot consume a code");
        android.os.SystemClock.offset = 0;

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        JSONObject future = subscription(false);
        long at = ((Number)future.get("serverNowMs")).longValue();
        future.put("queued",new JSONArray().put(term(ORDER,"ultra",12,at+1000,at+366L*86_400_000)))
                .put("paidUntilMs",at+366L*86_400_000);
        transport.next = new LunagramSubscriptionClient.Response(200,future); client.refresh(); DispatchQueue.drain();
        check(!client.subscription().active && client.subscription().planId == null && client.subscription().queued.size() == 1,
                "Future Ultra never grants immediate Ultra in current summary");
        boolean immutable = false;
        try { client.subscription().queued.clear(); } catch(UnsupportedOperationException expected) { immutable = true; }
        check(immutable,"Caller cannot rewrite paid queue observations");
        future.put("active",true).put("planId","ultra");
        transport.next = new LunagramSubscriptionClient.Response(200,future); client.refresh(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.BAD_RESPONSE && !client.subscription().active,"Inconsistent current/queued summary rejected atomically");

        for (String field : new String[]{"months","startsAtMs","orderId","planId"}) {
            BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
            JSONObject malformed = subscription(true);
            JSONObject current = (JSONObject)malformed.get("current");
            current.put(field,field.equals("months") ? 2 : field.equals("startsAtMs") ? -1 : "invalid");
            transport.next = new LunagramSubscriptionClient.Response(200,malformed); client.refresh(); DispatchQueue.drain();
            check(client.error() == LunagramSubscriptionClient.Error.BAD_RESPONSE && client.subscription() == null,"Malformed fixed term rejected: "+field);
        }
        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        JSONObject legacy = subscription(false); at = ((Number)legacy.get("serverNowMs")).longValue();
        legacy.put("legacy",true).put("legacyExpiresAtMs",at+86_400_000L).put("active",true).put("planId","pro")
                .put("expiresAtMs",at+86_400_000L).put("paidUntilMs",at+86_400_000L);
        transport.next = new LunagramSubscriptionClient.Response(200,legacy); client.refresh(); DispatchQueue.drain();
        check(client.subscription().legacy && client.subscription().current == null && client.subscription().queued.isEmpty(),"Legacy paid pool is explicit, never fabricated as an order");
        for (String code : new String[]{"UPGRADE_REQUIRED","CLIENT_UPDATE_REQUIRED","LEGACY_TERM_ACTIVE","QUOTE_EXPIRED","QUOTE_STALE","PLAN_RETIRED"}) {
            transport.next = new LunagramSubscriptionClient.Response(400,new JSONObject().put("error",code));
            client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
            check(client.error().name().equals(code),"V2 actionable error mapping: "+code);
        }

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,preview()); client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
        observed = client.quote();
        transport.next = new LunagramSubscriptionClient.Response(200,confirmed(observed));
        client.confirmCode();
        client.previewCode("short");
        check(client.quote() == observed && client.isBusy(),"A concurrent preview call cannot discard in-flight confirmation identity");
        BlackHolePrivateData.fail = true;
        DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.STORAGE && client.quote() == observed,
                "A committed response followed by local Keystore failure retains the exact retry quote");
        BlackHolePrivateData.fail = false;
        client.confirmCode(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.NONE && client.subscription().expiresAtMs == observed.expiresAtMs,
                "Keystore recovery retries committed quote without re-entering or consuming a second key");

        BlackHolePrivateData.reset(); transport = new Transport(); client = connected(transport);
        transport.next = new LunagramSubscriptionClient.Response(200,preview()); client.previewCode("ABCD1234EFGH5678"); DispatchQueue.drain();
        observed = client.quote();
        transport.next = new LunagramSubscriptionClient.Response(200,new JSONObject()); client.confirmCode(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.BAD_RESPONSE && client.quote() == observed,
                "Malformed confirmation response is ambiguous, not proof activation failed");
        transport.next = new LunagramSubscriptionClient.Response(200,confirmed(observed)); client.confirmCode(); DispatchQueue.drain();
        check(client.error() == LunagramSubscriptionClient.Error.NONE && client.quote() == null,"Malformed-response retry reconciles the original activation");
        cryptoContracts();
        pairingRecoveryContracts();
        httpsCapabilityContracts();
        System.out.println("PASS: "+checks+" billing-client contracts with fake transport/storage; no network, purchases, Android Keystore runtime, or JSON parser proof.");
    }
}
