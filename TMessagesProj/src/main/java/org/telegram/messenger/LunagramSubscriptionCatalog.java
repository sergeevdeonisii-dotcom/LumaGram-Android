package org.telegram.messenger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Display catalog only. Prices and input validation never establish a paid entitlement. */
public final class LunagramSubscriptionCatalog {
    public static final int ACTIVATION_CODE_LENGTH = 16;
    public static final int MAX_CODE_INPUT_LENGTH = 64;

    public enum Plan {
        BASED("based", "Based", 50, 40),
        STANDARD("standard", "Standard", 100, 70),
        PLUS("plus", "Plus", 140, 100),
        PRO("pro", "Pro", 200, 140),
        ULTRA("ultra", "Ultra", 500, 360);

        public final String id;
        public final String title;
        public final int monthlyUsdCents;
        // Owner-approved reference 50 Stars / $0.70, nearest ten; not a live exchange rate.
        public final int monthlyStars;

        Plan(String id, String title, int monthlyUsdCents, int monthlyStars) {
            this.id = id;
            this.title = title;
            this.monthlyUsdCents = monthlyUsdCents;
            this.monthlyStars = monthlyStars;
        }

        public String monthlyUsdAmount() {
            return String.format(Locale.US, "%d.%02d", monthlyUsdCents / 100, monthlyUsdCents % 100);
        }

        public int totalUsdCents(Period period) {
            requirePeriod(period);
            // All five approved monthly amounts are even cents; no fractional cent is lost.
            return monthlyUsdCents * period.priceNumerator / Period.PRICE_DENOMINATOR;
        }

        public int totalStars(Period period) {
            requirePeriod(period);
            // Apply the duration factor to the approved monthly Stars price, then HALF_UP
            // to the nearest ten. Work in half-Stars throughout (175 becomes 180).
            int halfStars = monthlyStars * period.priceNumerator;
            return (halfStars + 10) / 20 * 10;
        }

        public String totalUsdAmount(Period period) {
            int cents = totalUsdCents(period);
            return String.format(Locale.US, "%d.%02d", cents / 100, cents % 100);
        }
    }

    /** Exact display-only duration factors: 1, 2.5, 4 and 7 monthly prices. */
    public enum Period {
        MONTH_1(1, 2),
        MONTH_3(3, 5),
        MONTH_6(6, 8),
        MONTH_12(12, 14);

        public static final int PRICE_DENOMINATOR = 2;
        public final int months;
        public final int priceNumerator;

        Period(int months, int priceNumerator) {
            this.months = months;
            this.priceNumerator = priceNumerator;
        }

        public static Period fromMonths(int months) {
            for (Period period : values()) {
                if (period.months == months) return period;
            }
            throw new IllegalArgumentException("Unsupported subscription period");
        }
    }

    public enum PaymentMethod {
        TELEGRAM_STARS,
        CRYPTO_BOT_USDT,
        TON
    }

    public static final List<Plan> PLANS = Collections.unmodifiableList(Arrays.asList(Plan.values()));
    public static final List<Period> PERIODS = Collections.unmodifiableList(Arrays.asList(Period.values()));

    private LunagramSubscriptionCatalog() {
    }

    private static void requirePeriod(Period period) {
        if (period == null) throw new IllegalArgumentException("Subscription period is required");
    }

    /** Fail closed until the authenticated invoice/redemption service is actually connected. */
    public static boolean isPaymentAvailable(PaymentMethod method) {
        return false;
    }

    public static boolean isBotPurchaseAvailable() {
        return false;
    }

    /** ASCII-only, bounded input. Formatting is not evidence of validity or payment. */
    public static String normalizeActivationCode(CharSequence input) {
        if (input == null || input.length() > MAX_CODE_INPUT_LENGTH) {
            return null;
        }
        StringBuilder code = new StringBuilder(ACTIVATION_CODE_LENGTH);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 'a' + 'A');
            }
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))
                    || code.length() == ACTIVATION_CODE_LENGTH) {
                return null;
            }
            code.append(c);
        }
        return code.length() == ACTIVATION_CODE_LENGTH ? code.toString() : null;
    }
}
