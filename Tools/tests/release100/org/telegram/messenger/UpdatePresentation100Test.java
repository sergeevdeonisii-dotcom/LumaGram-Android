package org.telegram.messenger;

public final class UpdatePresentation100Test {
    private static int checks;
    private static void check(String input, String expected) {
        String actual = LumaUpdatePresentation.forDisplay(input,
                "https://raw.githubusercontent.com/fixture-owner/Fixture-Repo/main/updates/latest.json");
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Presentation mismatch: " + actual);
        checks++;
    }
    public static void main(String[] args) {
        check(null, null);
        check("", "");
        check("1.0.0", "1.0.0");
        check("Исправлены кружки и уведомления.", "Исправлены кружки и уведомления.");
        check("https://github.com/fixture-owner/Fixture-Repo/releases/tag/v1", "Lunagram");
        check("Source: raw.githubusercontent.com/fixture-owner/Fixture-Repo/main/latest.json", "Source: Lunagram");
        check("(https://api.github.com/repos/fixture-owner/Fixture-Repo)", "(Lunagram)");
        check("[Download](https://github.com/fixture-owner/Fixture-Repo)", "[Download](Lunagram)");
        check("@fixture-owner / Fixture-Repo", "@Lunagram / Lunagram");
        check("FIXTURE-OWNER, fixture-repo", "Lunagram, Lunagram");
        check("\u0422\u0435\u0441\u0442\nhttps://github.com/fixture-owner/Fixture-Repo\n1.0.0", "\u0422\u0435\u0441\u0442\nLunagram\n1.0.0");
        check("https://telegram.org", "https://telegram.org");
        check("fixture-owner-other", "fixture-owner-other");
        check("github.com.invalid", "github.com.invalid");
        System.out.println("PASS: " + checks + " production update presentation cases; transport fields are not changed.");
    }
}
