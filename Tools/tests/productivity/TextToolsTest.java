import org.telegram.messenger.LunagramTextTools;
import java.util.Random;
import java.util.Arrays;
import org.telegram.messenger.LunagramTextHistory;

public final class TextToolsTest {
    private static int checks;
    private static void eq(Object a, Object b) {
        checks++;
        if (!a.equals(b)) throw new AssertionError("Expected " + a + " but was " + b);
    }
    private static void rejects(Runnable task) {
        checks++;
        try { task.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input accepted");
    }
    public static void main(String[] args) {
        eq("Привет мир", LunagramTextTools.keyboardLayout("Ghbdtn vbh", true));
        eq("Hello world", LunagramTextTools.keyboardLayout("Руддщ цщкдв", false));
        eq("🐈中文", LunagramTextTools.keyboardLayout("🐈中文", true));
        eq("a b\n\nc d", LunagramTextTools.normalizeWhitespace(" \t a\u00a0  b \r\n\r\n\r\n c\t d  \n"));
        eq("👨‍👩‍👧‍👦 é \u200fאב", LunagramTextTools.normalizeWhitespace("👨‍👩‍👧‍👦 é \u200fאב"));
        eq("", LunagramTextTools.normalizeWhitespace("\r\n \u00a0\t"));
        eq("a\nb\nc", LunagramTextTools.normalizeWhitespace("a\u2028b\u2029c"));
        LunagramTextTools.Replacement r = LunagramTextTools.replace("aaa aaa", "aa", "$\\", true);
        eq("$\\a $\\a", r.text); eq(2, r.matches); eq(2, r.replaced);
        r = LunagramTextTools.replace("cat cat", "cat", "kitten", false);
        eq("kitten cat", r.text); eq(2, r.matches); eq(1, r.replaced);
        eq("a+b", LunagramTextTools.replace("a+b", ".*", "changed", true).text);
        eq("x", LunagramTextTools.replace("x", "", "changed", true).text);
        eq("🐈🐈", LunagramTextTools.replace("ab", "", "🐈", true).text.equals("ab") ? "🐈🐈" : "bad");
        eq("🐈🐈", LunagramTextTools.replace("aa", "a", "🐈", true).text);
        LunagramTextTools.Statistics s = LunagramTextTools.statistics("Hi 🐈\r\nмир");
        eq(2, s.words); eq(2, s.lines); eq(9, s.codePoints); eq(10, s.utf16Units); eq(15, s.utf8Bytes);
        eq(0, LunagramTextTools.statistics("").lines);
        eq(3, LunagramTextTools.statistics("a\u2028b\u2029c").lines);
        String limit = "x".repeat(LunagramTextTools.MAX_INPUT);
        eq(limit, LunagramTextTools.requireInput(limit));
        rejects(() -> LunagramTextTools.requireInput(limit + "x"));
        rejects(() -> LunagramTextTools.replace(limit, "x", "xx", true));
        rejects(() -> LunagramTextTools.requireInput("\ud83d"));
        rejects(() -> LunagramTextTools.requireInput("\udc00"));
        eq("", LunagramTextTools.requireInput(null));
        eq("[1] A B\none\n\n────────\n[2] C\n🐈", LunagramTextTools.combine(Arrays.asList(
                new LunagramTextTools.SourceText("A\nB", "one"), new LunagramTextTools.SourceText("C", "🐈"))));
        rejects(() -> LunagramTextTools.combine(Arrays.asList(new LunagramTextTools.SourceText("a", limit))));
        rejects(() -> LunagramTextTools.combine(null)); rejects(() -> LunagramTextTools.combine(Arrays.asList()));
        rejects(() -> new LunagramTextTools.SourceText("x".repeat(513), "a"));
        eq("[0, 6]", Arrays.toString(LunagramTextTools.find("Кот 🐈КОТ", "кот")));
        eq("[2]", Arrays.toString(LunagramTextTools.find("🐈.*", ".*")));
        eq(1000, LunagramTextTools.find(limit, "x").length);
        eq("[]", Arrays.toString(LunagramTextTools.find("cat", "")));
        eq("[2, 8, 11]", Arrays.toString(LunagramTextTools.paragraphStarts("  a\r\n\r\n b\n\n🐈")));
        eq("[]", Arrays.toString(LunagramTextTools.paragraphStarts(" \r\n\t")));
        for(String[] pair:new String[][]{{"🐈a🐈", "🐈b🐈"},{"same", "same"},{"", "🐈"},{"🐈", ""},{"a🐈", "a🐱"},{"a\nold\nz", "a\nnew\nz"}}){
            LunagramTextTools.Comparison diff=LunagramTextTools.compare(pair[0],pair[1]);
            eq(pair[0],diff.prefix+diff.removed+diff.suffix);eq(pair[1],diff.prefix+diff.added+diff.suffix);
            eq(pair[0].equals(pair[1]),diff.identical());
            for(String part:new String[]{diff.prefix,diff.removed,diff.added,diff.suffix})eq(part,LunagramTextTools.requireInput(part));
        }
        historyChecks();
        Random random = new Random(207);
        String[] parts = {"a", "Б", "🐈", " \t", "\r\n", "\u00a0", "👨‍👩‍👧", "\u200f", "文", "é"};
        for (int n = 0; n < 4000; n++) {
            StringBuilder input = new StringBuilder();
            for (int i = random.nextInt(60); i > 0; i--) input.append(parts[random.nextInt(parts.length)]);
            String value = input.toString(), normalized = LunagramTextTools.normalizeWhitespace(value);
            eq(normalized, LunagramTextTools.normalizeWhitespace(normalized));
            eq(value.replace("a", "$$"), LunagramTextTools.replace(value, "a", "$$", true).text);
            eq(value.codePointCount(0, value.length()), LunagramTextTools.statistics(value).codePoints);
            eq(normalized, LunagramTextTools.requireInput(normalized));
            String second=value.replace("Б", "hello🐈");
            LunagramTextTools.Comparison diff=LunagramTextTools.compare(value,second);
            eq(value,diff.prefix+diff.removed+diff.suffix);eq(second,diff.prefix+diff.added+diff.suffix);
        }
        System.out.println("PASS TextTools: " + checks + " checks (offline transforms, Unicode, limits, idempotence)");
    }
    private static void historyChecks(){
        LunagramTextHistory<String> h=new LunagramTextHistory<>();eq(false,h.canUndo());eq(false,h.canRedo());
        h.record("",0,0,0);h.record("a",1,1,1);h.record("ab",2,2,2);eq("a",h.undo().value);eq("",h.undo().value);eq(null==h.undo(),true);
        eq("a",h.redo().value);h.record("new",3,-1,100);eq(false,h.canRedo());eq(1,h.undo().start);
        h.redo();h.record("next",4,4,4);LunagramTextHistory.Entry<String> restored=h.undo();eq("new",restored.value);eq(0,restored.start);eq(3,restored.end);
        h.clear();eq(0,h.size());eq(0,h.retainedUnits());eq(false,h.canRedo());
        for(int i=0;i<100;i++)h.record("entry"+i,7,0,0);eq(LunagramTextHistory.MAX_STATES,h.size());
        h.clear();String huge="x".repeat(LunagramTextTools.MAX_INPUT-5);
        for(int i=0;i<10;i++)h.record(huge+i,huge.length()+1,0,0);eq(true,h.retainedUnits()<=LunagramTextHistory.MAX_UNITS);eq(4,h.size());
        h.record("bad",LunagramTextTools.MAX_INPUT+1,0,0);eq(0,h.size());
        // Opaque immutable snapshots (including Android SpannedString) must not be flattened.
        LunagramTextHistory<Object> opaque=new LunagramTextHistory<>();Object spans=new Object();opaque.record(spans,3,1,2);opaque.record(new Object(),4,4,4);eq(true,opaque.undo().value==spans);
    }
}
