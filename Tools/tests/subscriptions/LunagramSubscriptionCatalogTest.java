package org.telegram.messenger;

import java.util.Locale;

public final class LunagramSubscriptionCatalogTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String[] ids = {"ultra"};
        String[] titles = {"Ultra"};
        int[] cents = {249};
        int[] stars = {180};
        String[] amounts = {"2.49"};
        check(LunagramSubscriptionCatalog.PLANS.size() == 1, "Exactly one public Ultra plan");
        for (int i = 0; i < ids.length; i++) {
            LunagramSubscriptionCatalog.Plan plan = LunagramSubscriptionCatalog.PLANS.get(i);
            check(plan.id.equals(ids[i]), "Stable plan id and display order");
            check(plan.title.equals(titles[i]), "Requested public plan title");
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
        checkPeriods();
        checkHistoricalReceipts();
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
            check(LunagramSubscriptionCatalog.Plan.ULTRA.monthlyUsdAmount().equals("2.49"), "Catalog format is locale-independent");
            check(LunagramSubscriptionCatalog.Plan.ULTRA.totalUsdAmount(LunagramSubscriptionCatalog.Period.MONTH_3).equals("6.23"), "Duration amount formatting is locale-independent");
        } finally {
            Locale.setDefault(originalLocale);
        }
        System.out.println("PASS: " + checks + " subscription catalog/input checks; the display catalog does not grant entitlements.");
    }

    private static void checkPeriods() {
        int[] months = {1, 3, 6, 12};
        int[] numerators = {2, 5, 8, 14};
        int[][] expectedUsd = {{249,623,996,1743}};
        int[][] expectedStars = {{180,450,720,1260}};
        String[][] expectedAmounts = {{"2.49","6.23","9.96","17.43"}};
        check(LunagramSubscriptionCatalog.PERIODS.size() == 4, "Exactly four approved durations");
        check(LunagramSubscriptionCatalog.Period.PRICE_DENOMINATOR == 2, "Factors use integer halves, never a floating rate");
        for (int p = 0; p < months.length; p++) {
            LunagramSubscriptionCatalog.Period period = LunagramSubscriptionCatalog.PERIODS.get(p);
            check(period.months == months[p] && period.priceNumerator == numerators[p], "Stable duration order and exact approved factors");
            check(LunagramSubscriptionCatalog.Period.fromMonths(months[p]) == period, "Only whitelisted month counts resolve");
            for (int i = 0; i < LunagramSubscriptionCatalog.PLANS.size(); i++) {
                LunagramSubscriptionCatalog.Plan plan = LunagramSubscriptionCatalog.PLANS.get(i);
                int cents = plan.totalUsdCents(period), stars = plan.totalStars(period);
                check(cents == expectedUsd[i][p], "Exact expected whole-cent Ultra total for each duration");
                check(stars == expectedStars[i][p], "Exact expected Stars total for each Ultra duration");
                check(plan.totalUsdAmount(period).equals(expectedAmounts[i][p]), "Exact two-decimal total display for each combination");
                check(stars > 0 && stars % 10 == 0 && stars <= plan.monthlyStars * period.months, "Stars totals stay positive, nearest ten, within undiscounted cost");
                check(cents * 2 - plan.monthlyUsdCents * period.priceNumerator >= 0
                        && cents * 2 - plan.monthlyUsdCents * period.priceNumerator <= 1, "Half-cent rounds up, never truncates or adds a full cent");
                check(Math.abs(stars * 2 - plan.monthlyStars * period.priceNumerator) <= 10, "Rounding differs by at most five Stars");
                if (p > 0) {
                    check(cents > expectedUsd[i][p-1] && stars > expectedStars[i][p-1], "Larger durations increase total price");
                } else {
                    check(cents == plan.monthlyUsdCents && stars == plan.monthlyStars, "One month preserves existing approved prices");
                }
            }
        }
        check(LunagramSubscriptionCatalog.Plan.STANDARD.totalStars(LunagramSubscriptionCatalog.Period.MONTH_3) == 180, "175 Stars rounds HALF_UP to180, not170 or200");
        for (int invalid : new int[]{Integer.MIN_VALUE, -12, -1, 0, 2, 4, 5, 7, 11, 13, 24, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { LunagramSubscriptionCatalog.Period.fromMonths(invalid); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Unsupported month count is rejected, not coerced to one month");
        }
        for (LunagramSubscriptionCatalog.Plan plan : LunagramSubscriptionCatalog.PLANS) {
            boolean usdRejected = false, starsRejected = false, displayRejected = false;
            try { plan.totalUsdCents(null); } catch (IllegalArgumentException expected) { usdRejected = true; }
            try { plan.totalStars(null); } catch (IllegalArgumentException expected) { starsRejected = true; }
            try { plan.totalUsdAmount(null); } catch (IllegalArgumentException expected) { displayRejected = true; }
            check(usdRejected && starsRejected && displayRejected, "No absent-period fallback can silently price an order");
        }
        boolean immutable = false;
        try { LunagramSubscriptionCatalog.PERIODS.clear(); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Duration display catalog cannot be mutated");
    }

    private static void checkHistoricalReceipts() {
        int[][] usd = {{50,125,200,350}, {100,250,400,700}, {140,350,560,980},
                {200,500,800,1400}, {500,1250,2000,3500}};
        int[][] stars = {{40,100,160,280}, {70,180,280,490}, {100,250,400,700},
                {140,350,560,980}, {360,900,1440,2520}};
        for (LunagramSubscriptionCatalog.Plan plan : LunagramSubscriptionCatalog.Plan.values()) {
            check(LunagramSubscriptionCatalog.planFromId(plan.id) == plan, "Legacy enum IDs retained for immutable receipts");
            check(LunagramSubscriptionCatalog.isPurchasable(plan) == (plan == LunagramSubscriptionCatalog.Plan.ULTRA), "Legacy IDs cannot be new purchases");
            for (LunagramSubscriptionCatalog.Period period : LunagramSubscriptionCatalog.PERIODS) {
                int p = period.ordinal();
                check(LunagramSubscriptionCatalog.isKnownReceiptUsdPrice(plan.id, period, usd[plan.ordinal()][p]), "Exact old USD snapshot accepted");
                check(LunagramSubscriptionCatalog.isKnownReceiptStarsPrice(plan.id, period, stars[plan.ordinal()][p]), "Exact old Stars snapshot accepted");
                check(LunagramSubscriptionCatalog.isKnownReceiptUsdPrice(plan.id, period, plan.totalUsdCents(period)), "Current USD amount accepted");
                check(LunagramSubscriptionCatalog.isKnownReceiptStarsPrice(plan.id, period, plan.totalStars(period)), "Current Stars amount accepted");
                check(!LunagramSubscriptionCatalog.isKnownReceiptUsdPrice(plan.id, period, usd[plan.ordinal()][p] + 1), "Arbitrary historical USD price rejected");
                check(!LunagramSubscriptionCatalog.isKnownReceiptStarsPrice(plan.id, period, stars[plan.ordinal()][p] + 1), "Arbitrary historical Stars price rejected");
            }
        }
        for (String id : new String[]{null, "", "ULTRA", "ultra ", "free", "paid"}) {
            check(LunagramSubscriptionCatalog.planFromId(id) == null, "Unknown wire ID remains unknown");
            check(!LunagramSubscriptionCatalog.isKnownReceiptUsdPrice(id, LunagramSubscriptionCatalog.Period.MONTH_1, 249), "Unknown USD receipt fails closed");
            check(!LunagramSubscriptionCatalog.isKnownReceiptStarsPrice(id, LunagramSubscriptionCatalog.Period.MONTH_1, 180), "Unknown Stars receipt fails closed");
        }
        check(!LunagramSubscriptionCatalog.isPurchasable(null), "No absent plan can be purchased");
        check(!LunagramSubscriptionCatalog.isKnownReceiptUsdPrice("ultra", null, 249), "No absent receipt period");
        check(!LunagramSubscriptionCatalog.isKnownReceiptStarsPrice("ultra", null, 180), "No absent Stars receipt period");
    }
}
