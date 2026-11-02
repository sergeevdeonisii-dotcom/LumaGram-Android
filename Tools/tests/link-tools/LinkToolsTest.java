import org.telegram.messenger.LunagramLinkTools;
import java.util.Random;

public class LinkToolsTest {
    private static int checks;
    private static void eq(Object a, Object b) { checks++; if (!a.equals(b)) throw new AssertionError(a + " != " + b); }
    private static void bad(String s) { checks++; try { LunagramLinkTools.inspect(s); throw new AssertionError("Accepted invalid"); } catch (IllegalArgumentException ok) {} }
    public static void main(String[] args) {
        eq("https://a.test/p%2Fa?x=1+2&x=%2F#f?utm_source=a", LunagramLinkTools.clean("https://a.test/p%2Fa?utm_source=x&x=1+2&x=%2F&fbclid=42#f?utm_source=a").url);
        eq("https://a.test/", LunagramLinkTools.clean("https://a.test/?UTM_source=x&gclid=4").url);
        eq(1, LunagramLinkTools.clean("https://a.test/?%75tm_source=x").removed);
        for (String key : new String[]{"Signature","sig","X-Amz-Signature","token","auth","code","key","hash","expires"}) {
            String url = "https://a.test/?utm_source=x&"+key+"=secret";
            eq(url,LunagramLinkTools.clean(url).url); eq(true,LunagramLinkTools.clean(url).protectedQuery);
        }
        eq("https://a.test/?si=abc",LunagramLinkTools.clean("https://a.test/?si=abc").url);
        eq(true,LunagramLinkTools.inspect("https://bank.test@evil.test/path").credentials);
        eq("evil.test",LunagramLinkTools.inspect("https://bank.test@evil.test/path").asciiHost);
        eq("xn--e1afmkfd.xn--p1ai",LunagramLinkTools.inspect("https://пример.рф/a").asciiHost);
        eq(true,LunagramLinkTools.inspect("https://xn--e1afmkfd.xn--p1ai/a").international);
        eq(false,LunagramLinkTools.sameHost("https://a.test","https://b.test"));
        eq(true,LunagramLinkTools.sameHost("https://A.test/one","https://a.test/two"));
        eq("[::1]",LunagramLinkTools.inspect("https://[::1]:8080/a").asciiHost);
        eq(false,LunagramLinkTools.inspect("javascript:alert(1)").web);
        eq(true,LunagramLinkTools.inspect("http://example.test").insecure);
        eq("https://u:p@a.test/?utm_source=x",LunagramLinkTools.clean("https://u:p@a.test/?utm_source=x").url);
        for (String value:new String[]{"", "/relative", "https://", "https://a.test/\n", "https://a.test/%xx", "https://a.test/\ud800", "x".repeat(8193)}) bad(value);
        Random rng = new Random(41);
        for(int i=0;i<3000;i++) {
            String raw="https://a.test/p%2F"+rng.nextInt()+"?v=%2F+%26"+rng.nextInt()+"&same=1&same=2#frag";
            eq(raw,LunagramLinkTools.clean(raw).url);
            String tracked=raw.replace("?", "?utm_source=a&");
            eq(raw,LunagramLinkTools.clean(tracked).url);
        }
        System.out.println("Link tools: " + checks + " PASS");
    }
}
