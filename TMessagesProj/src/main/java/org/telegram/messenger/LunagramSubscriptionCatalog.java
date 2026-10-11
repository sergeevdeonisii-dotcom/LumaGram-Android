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
    }

    public enum PaymentMethod {
        TELEGRAM_STARS,
        CRYPTO_BOT_USDT,
        TON
    }

    public static final List<Plan> PLANS = Collections.unmodifiableList(Arrays.asList(Plan.values()));

    private LunagramSubscriptionCatalog() {
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
