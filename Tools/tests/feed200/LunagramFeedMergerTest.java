import org.telegram.messenger.LunagramFeedMerger;
import java.util.*;

public final class LunagramFeedMergerTest {
    static int assertions;
    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    static LunagramFeedMerger.Entry<String> post(long source, int id, int date) {
        return new LunagramFeedMerger.Entry<>(source, id, date, source + ":" + id);
    }
    static LunagramFeedMerger.Source<String> source(LunagramFeedMerger<String> merger, long channel) {
        return merger.sources().stream().filter(s -> s.channel == channel).findFirst().get();
    }
    static void chronologyAndFrontier() {
        LunagramFeedMerger<String> m = new LunagramFeedMerger<>(Arrays.asList(1L, 2L));
        var a = source(m, 1); var b = source(m, 2);
        m.accept(a, Arrays.asList(post(1, 10, 100), post(1, 9, 90)), 9, false);
        check(m.poll() == null, "Never emit while another channel head is unknown");
        m.accept(b, Arrays.asList(post(2, 20, 95), post(2, 19, 10)), 19, false);
        check(m.poll().date == 100, "Global newest");
        check(m.poll().date == 95, "Interleaved other channel");
        check(m.poll().date == 90, "Next known post");
        check(m.poll() == null, "Do not emit old B=10 before fetching next A page");
        m.accept(a, Arrays.asList(post(1, 8, 80), post(1, 7, 70)), 7, false);
        check(m.poll().date == 80 && m.poll().date == 70, "Page boundary chronological order");
        m.accept(a, Collections.emptyList(), 7, true);
        check(m.poll().date == 10, "Exhausted source no longer blocks");
        check(!m.isEnd(), "Need B next page before claiming history end");
        m.accept(b, Collections.emptyList(), 19, true);
        check(m.isEnd(), "All exhausted and drained");
    }
    static void retryCursorAndDuplicates() {
        LunagramFeedMerger<String> m = new LunagramFeedMerger<>(Arrays.asList(1L, 1L, -5L));
        var a = source(m, 1);
        check(m.sources().size() == 1, "Selection dedup and positive-only IDs");
        m.accept(a, Arrays.asList(post(1, 9, 90), post(1, 9, 90)), 9, false);
        check(m.poll().id == 9 && m.poll() == null, "Dedup exact channel/message identity");
        m.accept(a, Collections.emptyList(), 9, false);
        check(m.hasError() && !a.needsPage(), "Repeated server cursor stops request loop");
        m.retry();
        check(a.needsPage() && !m.hasError(), "Explicit retry reopens frontier");
        m.accept(a, Arrays.asList(post(1, 9, 90), post(1, 8, 80)), 8, false);
        check(m.poll().id == 8 && m.poll() == null, "Overlap page must not repeat prior post");
        m.accept(a, Collections.emptyList(), 4, false);
        check(a.needsPage() && a.offset == 4 && !m.isEnd(), "Service-only page advances, does not falsely end history");
        m.accept(a, Arrays.asList(post(2, 3, 30)), 3, false);
        check(m.poll() == null, "Reject message from different source");
    }
    static void tiesAndLiveUpdates() {
        LunagramFeedMerger<String> m = new LunagramFeedMerger<>(Arrays.asList(1L, 2L));
        var a = source(m, 1); var b = source(m, 2);
        m.accept(a, Arrays.asList(post(1, 5, 50), post(1, 4, 40)), 4, true);
        m.accept(b, Arrays.asList(post(2, 5, 50), post(2, 4, 40)), 4, true);
        check(m.poll().channel == 2 && m.poll().channel == 1, "Stable tie, identical message IDs in different channels survive");
        m.replace(1, 4, "edited");
        m.remove(2, Arrays.asList(4));
        check(m.poll().value.equals("edited"), "Edit pending post and remove deleted post");
        check(m.isEnd(), "Deleted item cannot reappear from buffer");
        LunagramFeedMerger<String> race = new LunagramFeedMerger<>(Arrays.asList(1L));
        race.remove(1, Arrays.asList(8));
        race.replace(1, 7, "edited during request");
        race.accept(source(race, 1), Arrays.asList(post(1, 8, 80), post(1, 7, 70)), 7, true);
        check(race.poll().value.equals("edited during request") && race.isEnd(), "In-flight stale response cannot resurrect deleted post or overwrite edit");
    }
    static void randomizedPaging() {
        Random random = new Random(200);
        for (int round = 0; round < 100; round++) {
            LunagramFeedMerger<String> m = new LunagramFeedMerger<>(Arrays.asList(1L, 2L, 3L, 4L));
            Map<Long, List<LunagramFeedMerger.Entry<String>>> data = new HashMap<>();
            Map<Long, Integer> cursor = new HashMap<>();
            int total = 0;
            for (var s : m.sources()) {
                var entries = new ArrayList<LunagramFeedMerger.Entry<String>>();
                int count = random.nextInt(70), date = 10000;
                for (int id = count; id > 0; id--) { date -= random.nextInt(10) + 1; entries.add(post(s.channel, id, date)); }
                data.put(s.channel, entries); cursor.put(s.channel, 0); total += count;
            }
            int count = 0, last = Integer.MAX_VALUE, guard = 0;
            while (!m.isEnd() && guard++ < 1000) {
                for (var s : m.sources()) if (s.needsPage()) {
                    var entries = data.get(s.channel); int start = cursor.get(s.channel), end = Math.min(entries.size(), start + random.nextInt(7) + 1);
                    var page = entries.subList(start, end);
                    m.accept(s, page, page.isEmpty() ? s.offset : page.get(page.size() - 1).id, end == entries.size());
                    cursor.put(s.channel, end);
                }
                LunagramFeedMerger.Entry<String> entry;
                while ((entry = m.poll()) != null) { check(entry.date <= last, "Randomized chronology"); last = entry.date; count++; }
            }
            check(count == total && m.isEnd(), "Randomized pagination completeness, no duplicates or losses");
        }
    }
    public static void main(String[] args) {
        chronologyAndFrontier(); retryCursorAndDuplicates(); tiesAndLiveUpdates(); randomizedPaging();
        System.out.println("PASS feed merge: " + assertions + " assertions");
    }
}
