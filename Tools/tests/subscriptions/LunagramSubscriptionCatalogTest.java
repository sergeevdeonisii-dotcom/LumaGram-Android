package org.telegram.messenger;

import java.util.Locale;

public final class LunagramSubscriptionCatalogTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String[] ids = {"based", "standard", "plus", "pro", "ultra"};
        String[] titles = {"Based", "Standard", "Plus", "Pro", "Ultra"};
        int[] cents = {50, 100, 140, 200, 500};
        int[] stars = {40, 70, 100, 140, 360};
        String[] amounts = {"0.50", "1.00", "1.40", "2.00", "5.00"};
        check(LunagramSubscriptionCatalog.PLANS.size() == 5, "Exactly the requested five plans");
        for (int i = 0; i < ids.length; i++) {
            LunagramSubscriptionCatalog.Plan plan = LunagramSubscriptionCatalog.PLANS.get(i);
            check(plan.id.equals(ids[i]), "Stable plan id and display order");
            check(plan.title.equals(titles[i]), "Requested plan title, including Based");
            check(plan.monthlyUsdCents == cents[i], "Exact monthly cents without floating point");
            check(plan.monthlyStars == stars[i], "Owner-approved nearest-ten Star tariffs");
            check(plan.monthlyUsdAmount().equals(amounts[i]), "Exact two-decimal display amount");
        }
        boolean immutable = false;
        try {
            LunagramSubscriptionCatalog.PLANS.clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        check(immutable, "Display catalog cannot be mutated by a caller");
        check(LunagramSubscriptionCatalog.PaymentMethod.values().length == 3, "Stars plus the two requested crypto methods");
        for (LunagramSubscriptionCatalog.PaymentMethod method : LunagramSubscriptionCatalog.PaymentMethod.values()) {
            check(!LunagramSubscriptionCatalog.isPaymentAvailable(method), "Unconfigured payments fail closed");
        }
        check(!LunagramSubscriptionCatalog.isPaymentAvailable(null), "Missing method cannot create an invoice");
        check(!LunagramSubscriptionCatalog.isBotPurchaseAvailable(), "Unknown bot cannot be opened");

        check("ABCD1234EFGH5678".equals(LunagramSubscriptionCatalog.normalizeActivationCode("ABCD1234EFGH5678")), "Plain code");
        check("ABCD1234EFGH5678".equals(LunagramSubscriptionCatalog.normalizeActivationCode("abcd-1234-efgh-5678")), "Grouped lowercase code");
        check("ABCD1234EFGH5678".equals(LunagramSubscriptionCatalog.normalizeActivationCode("  abcd 1234 efgh 5678  ")), "Whitespace grouping");
        check("0000000000000000".equals(LunagramSubscriptionCatalog.normalizeActivationCode("0000-0000-0000-0000")), "All ASCII digits are format-valid, not proof of activation");
        for (String invalid : new String[]{null, "", "----    ", "ABCD1234EFGH567", "ABCD1234EFGH56789",
                "ABCD1234EFGH567!", "АBCD1234EFGH5678", "ABCD1234EFGH567é", "ＡBCD1234EFGH5678",
                "ABCD\t1234EFGH5678", "ABCD\n1234EFGH5678", "ABCD\u200b1234EFGH5678",
                "ABCD\u00a01234EFGH5678", "ABCD/1234EFGH5678", "ABCD_1234EFGH5678",
                "                                                                ABCD1234EFGH5678"}) {
            check(LunagramSubscriptionCatalog.normalizeActivationCode(invalid) == null, "Invalid or unbounded code rejected");
        }
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            check("IIII1111IIII1111".equals(LunagramSubscriptionCatalog.normalizeActivationCode("iiii-1111-iiii-1111")), "ASCII code normalization is locale-independent");
            check(LunagramSubscriptionCatalog.Plan.PLUS.monthlyUsdAmount().equals("1.40"), "Catalog format is locale-independent");
        } finally {
            Locale.setDefault(originalLocale);
        }
        System.out.println("PASS: " + checks + " subscription catalog/input checks; the display catalog does not grant entitlements.");
    }
}
