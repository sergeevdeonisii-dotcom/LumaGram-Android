package org.telegram.messenger;

import org.json.JSONObject;
import org.json.JSONArray;
import org.telegram.tgnet.TLRPC;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.telegram.messenger.LunagramWorkspaceModel.*;
import static org.telegram.messenger.LunagramWorkspaceStore.*;

public final class WorkspaceTest {
    private static int checks;
    private interface Throwing { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void invalid(Throwing action, String label) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | org.json.JSONException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    private static void failure(Failure failure, Throwing action, String label) throws Exception {
        try { action.run(); } catch (WorkspaceException expected) { check(expected.failure == failure, label + " type: " + expected.failure); return; }
        throw new AssertionError(label);
    }
    private static Entry item(Kind kind) {
        return Entry.create(kind, "Имя 🐈", kind == Kind.BOOKMARK || kind == Kind.MARKER ? "" : "private-text\n\"quote\" \\ 🐈",
                kind == Kind.BOOKMARK || kind == Kind.CHECKLIST ? "Личное" : "", kind == Kind.BOOKMARK || kind == Kind.MARKER ? 91 : 0);
    }
    private static Session session() { return new Session(0, 23, false, () -> true); }
    private static void reset() {
        UserConfig.selectedAccount = 0;
        for (int i = 0; i < 4; i++) { UserConfig.values[i].owner = 101 + i; LunagramSubscriptionClient.epochs[i] = 1; MessagesController.values[i].chats.clear(); }
        LunagramSubscriptionAccess.paid = true;
        SharedConfig.appLocked = SharedConfig.isWaitingForPasscodeEnter = BlackHoleVault.locked = false;
        BlackHolePrivateData.available = true; BlackHolePrivateData.failRead = BlackHolePrivateData.failWrite = false;
        BlackHolePrivateData.beforeRead = BlackHolePrivateData.beforeWrite = null; BlackHolePrivateData.writes = 0;
        BlackHolePrivateData.data.clear(); BlackHolePrivateData.keys.clear();
    }
    private static void model() throws Exception {
        ArrayList<Entry> items = new ArrayList<>();
        for (Kind kind : Kind.values()) items.add(item(kind));
        Document document = new Document(23, items);
        String encoded = encode(document);
        Document decoded = decode(encoded, 23);
        check(decoded.entries.size() == 6, "six kinds roundtrip");
        for (int i = 0; i < 6; i++) {
            Entry a = items.get(i), b = decoded.entries.get(i);
            check(a.id.equals(b.id) && a.name.equals(b.name) && a.text.equals(b.text) && a.kind == b.kind
                    && a.collection.equals(b.collection) && a.messageId == b.messageId && a.created == b.created, "lossless Unicode roundtrip " + a.kind);
        }
        items.clear(); check(document.entries.size() == 6, "defensive list copy");
        try { document.entries.clear(); throw new AssertionError("mutable list"); } catch (UnsupportedOperationException expected) { checks++; }
        check(decode(null, 23).entries.isEmpty(), "missing is empty");
        invalid(() -> decode("", 23), "empty corruption is not missing");
        invalid(() -> decode(encoded + " garbage", 23), "trailing data rejected");
        invalid(() -> decode(encoded, 24), "wrong chat rejected");
        for (Object version : new Object[]{2, "1", 1.25, JSONObject.NULL, true}) {
            JSONObject object = new JSONObject(encoded).put("version", version);
            invalid(() -> decode(object.toString(), 23), "version type/value");
        }
        JSONObject unknown = new JSONObject(encoded).put("surprise", 1);
        invalid(() -> decode(unknown.toString(), 23), "unknown envelope key");
        JSONObject missing = new JSONObject(encoded); missing.remove("dialog");
        invalid(() -> decode(missing.toString(), 23), "missing envelope key");
        JSONObject stringArray = new JSONObject(encoded).put("entries", "[]");
        invalid(() -> decode(stringArray.toString(), 23), "array type");
        for (String key : new String[]{"id", "kind", "name", "text", "collection", "message", "checked", "created"}) {
            JSONObject object = new JSONObject(encoded);
            object.getJSONArray("entries").getJSONObject(0).put(key, JSONObject.NULL);
            invalid(() -> decode(object.toString(), 23), "typed field " + key);
        }
        JSONObject duplicate = new JSONObject(encoded); duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").getJSONObject(0));
        invalid(() -> decode(duplicate.toString(), 23), "duplicate id");
        invalid(() -> new Document(23, Arrays.asList(item(Kind.MARKER), item(Kind.MARKER))), "duplicate marker");
        Entry marker = item(Kind.MARKER);
        Document replaceMarker = document.put(marker);
        check(replaceMarker.entries.size() == 6 && replaceMarker.find(marker.id) != null, "one marker replaces prior marker");
        Entry checklist = item(Kind.CHECKLIST);
        Document toggled = document.put(checklist).put(checklist.toggled());
        check(toggled.find(checklist.id).checked && toggled.entries.size() == 7, "toggle replaces one entry");
        check(toggled.remove(checklist.id).entries.size() == 6, "delete one entry");
        invalid(() -> item(Kind.DRAFT).toggled(), "checkbox only checklists");
        invalid(() -> Entry.create(Kind.BOOKMARK, "label", "message body", "named", 1), "bookmark never contains message content");
        invalid(() -> Entry.create(Kind.MARKER, "label", "message body", "", 1), "marker only reference");
        invalid(() -> Entry.create(Kind.SEARCH, "query", "q".repeat(MAX_QUERY + 1), "", 0), "query bound");
        invalid(() -> Entry.create(Kind.DRAFT, "d", "d".repeat(MAX_TEXT + 1), "", 0), "text bound");
        invalid(() -> Entry.create(Kind.TEMPLATE, "n".repeat(MAX_NAME + 1), "text", "", 0), "name bound");
        invalid(() -> Entry.create(Kind.CHECKLIST, "label", "text", "", 0), "named list required");
        invalid(() -> Entry.create(Kind.BOOKMARK, "label", "", "named", 0), "positive reference");
        invalid(() -> Entry.create(Kind.TEMPLATE, "label", "text", "", 1), "no unexpected message ref");
        invalid(() -> Entry.create(Kind.SEARCH, "label", " ", "", 0), "blank query rejected");
        for (String bad : new String[]{"\ud800", "\udfff", "\ud800a", "a\udfff"}) {
            invalid(() -> Entry.create(Kind.TEMPLATE, "name", bad, "", 0), "invalid Unicode text rejected without replacement");
            invalid(() -> Entry.create(Kind.TEMPLATE, bad, "text", "", 0), "invalid Unicode name rejected without replacement");
            invalid(() -> Entry.create(Kind.CHECKLIST, "name", "text", bad, 0), "invalid Unicode collection rejected without replacement");
        }
        ArrayList<Entry> bounded = new ArrayList<>(); for (int i = 0; i < MAX_ITEMS; i++) bounded.add(item(Kind.TEMPLATE));
        Document max = new Document(23, bounded);
        check(decode(encode(max), 23).entries.size() == MAX_ITEMS, "128 item bound accepted");
        invalid(() -> max.put(item(Kind.TEMPLATE)), "129th rejected");
        ArrayList<Entry> big = new ArrayList<>(); for (int i = 0; i < 20; i++) big.add(Entry.create(Kind.TEMPLATE, "huge", "Я".repeat(MAX_TEXT), "", 0));
        invalid(() -> encode(new Document(23, big)), "encoded UTF8 byte cap");
        invalid(() -> decode("x".repeat(MAX_BYTES + 1), 23), "raw cap before parser");
        invalid(() -> decode("Я".repeat(MAX_BYTES / 2 + 1), 23), "UTF8 cap before parser");
        // Literal text is data: never interpreted as SQL, code, or Telegram operations.
        Entry literal = Entry.create(Kind.SEARCH, "literal", "'); DROP TABLE x; -- <script> hi", "", 0);
        check(decode(encode(new Document(23, Arrays.asList(literal))), 23).entries.get(0).text.equals(literal.text), "literal query roundtrip");
    }
    private static void store() throws Exception {
        reset(); final Session s = session(); Entry template = item(Kind.TEMPLATE);
        check(load(s).entries.isEmpty(), "new account empty"); put(s, template);
        check(load(s).find(template.id) != null, "saved data read");
        byte[] encrypted = BlackHolePrivateData.data.values().iterator().next().clone();
        check(!new String(encrypted, StandardCharsets.UTF_8).contains("private-text"), "no plaintext fixture persistence");
        check(exportJson(s).contains("private-text"), "explicit plaintext export");
        LunagramSubscriptionAccess.paid = false;
        failure(Failure.SUBSCRIPTION, () -> put(s, item(Kind.TEMPLATE)), "expired mutation denied");
        check(load(s).find(template.id) != null && exportJson(s).contains(template.id), "expired existing data accessible");
        delete(s, template.id); check(load(s).entries.isEmpty(), "expired delete allowed");
        LunagramSubscriptionAccess.paid = true; put(s, template);
        LunagramSubscriptionClient.epochs[0]++;
        failure(Failure.LOCKED, () -> load(s), "same owner relogin rejected");
        failure(Failure.LOCKED, () -> put(s, template), "stale write rejected");
        failure(Failure.LOCKED, () -> exportJson(s), "stale export rejected");
        failure(Failure.LOCKED, () -> delete(s, template.id), "stale delete rejected");
        final Session current = session();
        UserConfig.selectedAccount = 1;
        failure(Failure.LOCKED, () -> load(current), "selected account changed");
        Session second = new Session(1, 23, false, () -> true);
        check(load(second).entries.isEmpty(), "different account isolated"); put(second, item(Kind.DRAFT));
        UserConfig.selectedAccount = 0; check(load(current).entries.size() == 1, "first account preserved");
        UserConfig.values[0].owner = 0;
        failure(Failure.LOCKED, () -> load(current), "logout rejected");
        UserConfig.values[0].owner = 999;
        failure(Failure.LOCKED, () -> load(current), "new owner rejected");
        check(load(session()).entries.isEmpty(), "new owner cannot see old data");
        reset(); final Session secure = session();
        BlackHoleVault.locked = true;
        failure(Failure.LOCKED, () -> load(secure), "vault gate");
        BlackHoleVault.locked = false; SharedConfig.appLocked = true;
        failure(Failure.LOCKED, () -> load(secure), "app passcode gate");
        SharedConfig.appLocked = false; SharedConfig.isWaitingForPasscodeEnter = true;
        failure(Failure.LOCKED, () -> load(secure), "waiting passcode gate");
        SharedConfig.isWaitingForPasscodeEnter = false;
        check(!new Session(-1, 23, false, () -> true).isCurrent(), "negative account safe");
        check(!new Session(9, 23, false, () -> true).isCurrent(), "large account safe");
        check(!new Session(0, 0, false, () -> true).isCurrent(), "zero chat denied");
        check(!new Session(0, 23, true, () -> true).isCurrent(), "protected context denied");
        check(!new Session(0, 23, false, null).isCurrent(), "missing context denied");
        check(!new Session(0, 23, false, () -> {throw new RuntimeException();}).isCurrent(), "context error fail closed");
        for (long id : new long[]{4294967296L, 2305843009213693953L}) check(!new Session(0,id,false,()->true).isCurrent(), "secret/folder denied");
        AtomicBoolean context = new AtomicBoolean(true);
        Session dynamic = new Session(0, 23, false, context::get); put(dynamic, template); context.set(false);
        failure(Failure.LOCKED, () -> exportJson(dynamic), "dynamic TTL/noforwards context revocation");
        Session group = new Session(0, -56, false, () -> true);
        check(!group.isCurrent(), "unknown channel denied");
        TLRPC.Chat chat = new TLRPC.Chat(); MessagesController.values[0].chats.put(56L, chat);
        check(group.isCurrent(), "known chat allowed");
        chat.noforwards=true; check(!group.isCurrent(), "noforwards denied"); chat.noforwards=false;
        chat.left=true; check(!group.isCurrent(), "left denied"); chat.left=false;
        chat.kicked=true; check(!group.isCurrent(), "kicked denied"); chat.kicked=false;
        BlackHolePrivateData.available = false;
        failure(Failure.STORAGE, () -> load(secure), "unavailable crypto denies read");
        failure(Failure.STORAGE, () -> put(secure, template), "unavailable crypto no fallback");
        BlackHolePrivateData.available=true; BlackHolePrivateData.failRead=true;
        int priorWrites=BlackHolePrivateData.writes;
        failure(Failure.STORAGE, () -> put(secure, template), "unreadable does not overwrite");
        check(priorWrites==BlackHolePrivateData.writes, "read failure preserved bytes");
        BlackHolePrivateData.failRead=false; BlackHolePrivateData.failWrite=true;
        failure(Failure.STORAGE, () -> put(secure, template), "write errors explicit");
        BlackHolePrivateData.failWrite=false;
        String key="lunagram_workspace_v1_23";
        BlackHolePrivateData.write(0,101,key,"{broken");
        byte[] before=BlackHolePrivateData.data.get(BlackHolePrivateData.binding(0,101,key)).clone();
        failure(Failure.CORRUPT, () -> put(secure, template), "corrupt store not overwritten");
        failure(Failure.CORRUPT, () -> delete(secure, template.id), "corrupt delete not overwritten");
        check(Arrays.equals(before, BlackHolePrivateData.data.get(BlackHolePrivateData.binding(0,101,key))), "corrupt bytes preserved");
        // Authentication failure also preserves the original encrypted bytes, not an empty replacement.
        before[before.length-1]^=1; BlackHolePrivateData.data.put(BlackHolePrivateData.binding(0,101,key),before);
        failure(Failure.STORAGE, () -> put(secure, template), "GCM tamper fail closed");
        check(Arrays.equals(before, BlackHolePrivateData.data.get(BlackHolePrivateData.binding(0,101,key))), "tampered bytes preserved");
        reset(); final Session race = session();
        BlackHolePrivateData.beforeRead=()->LunagramSubscriptionClient.epochs[0]++;
        failure(Failure.LOCKED, () -> load(race), "identity rechecked after storage read");
        reset(); final Session writeRace = session();
        BlackHolePrivateData.beforeWrite=()->UserConfig.values[0].owner=999;
        failure(Failure.STORAGE, () -> put(writeRace,template), "owner bound write rejects race");
        check(BlackHolePrivateData.data.isEmpty(), "no cross-owner write");
        reset(); final Session cap = session();
        for (int i=0;i<MAX_ITEMS;i++) put(cap,item(Kind.TEMPLATE));
        failure(Failure.INVALID, () -> put(cap,template), "store item cap explicit");
        check(load(cap).entries.size()==128, "cap failure preserves data");
    }
    private static void sources(Path repo) throws Exception {
        String activity=Files.readString(repo.resolve("TMessagesProj/src/main/java/org/telegram/ui/LunagramWorkspaceActivity.java"));
        check(activity.contains("WeakReference<Host>"), "weak host lifetime");
        check(activity.contains("value.captureDraftApply()") && activity.contains("draftApply.apply(entry.text)"), "capture immutable draft token before preview");
        check(activity.indexOf("value.captureDraftApply()") < activity.indexOf("previewDraft = draft();"), "capture before draft preview");
        check(activity.contains("if (!draftPreviewCaptured)"), "resume never silently recaptures a stale draft token");
        check(activity.contains("new LunagramTextSelectionGuard(this::available"), "native selection guard");
        check(activity.contains("if (!available()) { setText(\"\"); return true; }"), "native context action guard");
        check(activity.contains("LunagramWorkspaceReplaceConfirm"), "nonempty draft confirmation");
        check(activity.contains("LunagramWorkspaceExportConfirm"), "explicit clipboard export warning");
        check(!activity.contains("setTextIsSelectable(true)"), "clipboard only guarded actions");
        check(activity.contains("fragment).session != session) break;"), "close only same-session contiguous workspace");
        check(activity.contains("layout.getLastFragment() != this"), "never close unrelated top fragment");
        check(activity.contains("if (session.isCurrent()) action.run();"), "navigation rechecks identity after close");
        String config=Files.readString(repo.resolve("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"));
        check(config.contains("synchronized (LunagramWorkspaceStore.class) { }"), "logout serialization barrier");
        String production=Files.readString(repo.resolve("TMessagesProj/src/main/java/org/telegram/messenger/LunagramWorkspaceStore.java"));
        check(production.contains("BlackHolePrivateData.write(session.account, session.owner,"), "captured owner encryption target");
        check(production.contains("public static synchronized void put") && production.contains("public static synchronized void delete"), "sync writes fence logout barrier");
    }
    public static void main(String[] args) throws Exception {
        model(); store(); sources(Path.of(args[0]));
        System.out.println("PASS " + checks + " workspace model/store/encryption and UI source-contract checks (not Android UI runtime)");
    }
}
