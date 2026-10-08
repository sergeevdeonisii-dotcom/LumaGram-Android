param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $OutputRoot) { $OutputRoot = Join-Path $PSScriptRoot '.runs' }
$javaBin = Join-Path $JavaHome 'bin'
if (-not (Test-Path -LiteralPath (Join-Path $javaBin 'javac.exe'))) { throw 'Pass a JDK path through -JavaHome.' }
function Source([string]$path) { [System.IO.File]::ReadAllText((Join-Path $repo $path)) }
function Check([bool]$condition, [string]$description) {
    if (-not $condition) { throw $description }
    Write-Output "PASS (source): $description"
}
$javaRoot = 'TMessagesProj/src/main/java/org/telegram/'
$enter = Source ($javaRoot + 'ui/Components/ChatActivityEnterView.java')
$storage = Source ($javaRoot + 'messenger/MessagesStorage.java')
$controller = Source ($javaRoot + 'messenger/MessagesController.java')
$chat = Source ($javaRoot + 'ui/ChatActivity.java')
$split = [regex]::Match($enter, '(?s)public boolean processSendingText\(.*?do \{\s*(int whitespaceIndex = -1;.*?)\s*CharSequence part = text\.subSequence\(start, end\);.*?\s*(start = end(?: \+ 1)?;)\s*\} while \(end != text\.length\(\)\);')
if (-not $split.Success) { throw 'Could not extract the actual text split boundary/advancement code.' }
$batchStart = $storage.IndexOf('private ArrayList<Long> markMessagesAsDeletedInternal(long dialogId, ArrayList<Integer> messages, boolean deleteFiles, int mode, int threadMessageId, boolean forceLocalRemoval)')
if ($batchStart -lt 0) { throw 'Could not find the actual retained-deletion storage batch.' }
$deleteBatch = $storage.Substring($batchStart)
$dialogGuard = [regex]::Match($deleteBatch, '(?m)^\s*if \((did != currentUser && [^\r\n]+)\) \{\s*$')
$topicGuard = [regex]::Match($deleteBatch, '(?m)^\s*if \((topicId != 0 && [^\r\n]+)\) \{\s*$')
$snapshot = [regex]::Match($storage, 'final Set<String> deletedBeforeBatch = LumaDeletedMessages\.snapshotDeleted\(currentAccount\);')
Check ($dialogGuard.Success -and $topicGuard.Success -and $snapshot.Success) 'Both counter guards use one pre-batch tombstone snapshot'
Check ([regex]::Matches($storage, [regex]::Escape($snapshot.Value)).Count -eq 1 -and $snapshot.Index -lt $storage.IndexOf('LumaDeletedMessages.rememberDeleted(')) 'Snapshot is captured before either message table records deletions'
$range = [regex]::Match($controller, '(?s)public void deleteMessagesRange\(.*?(?=\r?\n    public void setCustomChatReactions\()').Value
Check ($range.Contains('markMessagesAsDeleted(dialogId, dbMessages, false, true, 0, 0, true)')) 'Explicit date-range deletion bypasses retained storage'
Check ($range.Contains('NotificationCenter.messagesDeleted, dbMessages, channelId, false, false, false, 0, null, true)')) 'Explicit date-range deletion passes forceLocalRemoval in UI argument 7'
Check (-not $chat.Contains('LumaDeletedMessages.rememberDeleted(') -and $chat.Contains('obj.lumaRetainedDeleted = true')) 'UI keeps its immediate retained flag but storage is the sole persistent tombstone writer'

# Compile only the real identity/tombstone helpers and extracted production expressions.
# The queue/counter harness is a model, not SQLite, Telegram transport or Android UI testing.
$run = Join-Path $OutputRoot ([guid]::NewGuid().ToString() + '/personal-message80')
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$testSource = @'
package org.telegram.messenger;
import java.util.*;
public final class LumaPersonalMessage80Test {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static List<String> parts(CharSequence text, int maxLength) {
        List<String> result = new ArrayList<>();
        if (text.length() == 0) return result;
        int start = 0, end;
        do {
            __SPLIT__
            check(end > start, "split makes forward progress");
            result.add(text.subSequence(start, end).toString());
            __ADVANCE__
        } while (end != text.length());
        return result;
    }
    private static boolean dialogCount(long did, long currentUser, int mid, Set<String> deletedBeforeBatch) {
        return __DIALOG_GUARD__;
    }
    private static boolean topicCount(long topicId, long did, int mid, Set<String> deletedBeforeBatch) {
        return __TOPIC_GUARD__;
    }
    private static void splitCase(String text) {
        List<String> split = parts(text, 4096);
        check(String.join("", split).equals(text), "split boundaries preserve every UTF-16 unit");
        for (String part : split) {
            check(part.length() <= 4096, "part stays under the message limit");
            check(!Character.isHighSurrogate(part.charAt(part.length() - 1)), "no trailing half-surrogate");
            check(!Character.isLowSurrogate(part.charAt(0)), "no leading half-surrogate");
        }
    }
    private static int[] batch(int currentAccount, long did, int[] mids, boolean forceLocalRemoval) {
        __SNAPSHOT__
        int[] counters = new int[5]; // dialog unread/mention, topic unread/mention/total
        for (int mid : mids) {
            if (LumaDeletedMessages.shouldRetain(currentAccount, forceLocalRemoval, false, false)) {
                LumaDeletedMessages.rememberDeleted(currentAccount, did, mid);
            }
            if (dialogCount(did, UserConfig.ids[currentAccount], mid, deletedBeforeBatch)) {
                counters[0]++; counters[1]++;
            }
        }
        // Main-table tombstones must not suppress the first topic-table counter update.
        for (int mid : mids) {
            if (LumaDeletedMessages.shouldRetain(currentAccount, forceLocalRemoval, false, false)) {
                LumaDeletedMessages.rememberDeleted(currentAccount, did, mid);
            }
            if (topicCount(10, did, mid, deletedBeforeBatch)) {
                counters[2]++; counters[3]++; counters[4]++;
            }
        }
        if (forceLocalRemoval) {
            List<Integer> removed = new ArrayList<>();
            for (int mid : mids) removed.add(mid);
            LumaDeletedMessages.forgetDeleted(currentAccount, did, removed);
        }
        return counters;
    }
    private static void expect(int[] counters, int value, String message) {
        for (int counter : counters) check(counter == value, message);
    }
    public static void main(String[] args) {
        splitCase("a".repeat(4096));
        splitCase("a".repeat(4097));
        splitCase("a".repeat(8193));
        splitCase("a".repeat(4095) + "\ud83d\ude80" + "z".repeat(4096));
        splitCase("word. ".repeat(1800));
        splitCase(("line\n\nnext\n").repeat(1800));
        Random random = new Random(80);
        for (int sample = 0; sample < 20; sample++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 9000; i++) text.append(random.nextInt(7) == 0 ? "\ud83d\ude80" : "x");
            splitCase(text.toString());
        }
        System.out.println("PASS (extracted Java): long unbroken text, whitespace/newlines, surrogate boundaries");
        UserConfig.ids[0] = 80001;
        LumaDeletedMessages.setEnabled(0, true);
        final boolean[] immediateUiFlag = { false };
        ArrayDeque<Runnable> storageQueue = new ArrayDeque<>();
        storageQueue.add(() -> expect(batch(0, -80, new int[] {17, 18}, false), 2, "first deletion counts once in both tables"));
        // Exercise the UI-before-storage ordering without a second persistent writer.
        immediateUiFlag[0] = true;
        check(immediateUiFlag[0] && !LumaDeletedMessages.isDeleted(0, -80, 17), "UI flag is immediate; storage still owns tombstones");
        storageQueue.remove().run();
        storageQueue.add(() -> expect(batch(0, -80, new int[] {17, 18}, false), 0, "replayed deletion does not subtract other unread messages"));
        storageQueue.remove().run();
        expect(batch(0, -80, new int[] {18, 19}, false), 1, "mixed known/new packet counts only the newly deleted message");
        Set<String> before = LumaDeletedMessages.snapshotDeleted(0);
        LumaDeletedMessages.rememberDeleted(0, -80, 20);
        check(!LumaDeletedMessages.isDeleted(before, -80, 20), "snapshot is independent of later writes");
        before.clear();
        check(LumaDeletedMessages.isDeleted(0, -80, 17), "snapshot mutations cannot erase persisted tombstones");
        expect(batch(0, -80, new int[] {17}, true), 0, "explicitly removing a retained row does not subtract twice");
        check(!LumaDeletedMessages.isDeleted(0, -80, 17), "explicit removal clears its tombstone");
        expect(batch(0, -80, new int[] {21}, true), 1, "explicit removal of a fresh row still counts");
        check(!LumaDeletedMessages.isDeleted(0, -80, 21), "explicit removal never creates a retained tombstone");
        UserConfig.ids[0] = 80002;
        LumaDeletedMessages.setEnabled(0, true);
        expect(batch(0, -80, new int[] {18}, false), 1, "replacement account does not inherit old tombstones");
        System.out.println("PASS (helper/model Java): first/replayed/mixed queued deletion, explicit removal, snapshot/account isolation");
        System.out.println("Checks: " + checks + "; not an Android/SQLite/transport compile or device test.");
    }
}
'@
$testSource = $testSource.Replace('__SPLIT__', $split.Groups[1].Value).Replace('__ADVANCE__', $split.Groups[2].Value)
$testSource = $testSource.Replace('__DIALOG_GUARD__', $dialogGuard.Groups[1].Value).Replace('__TOPIC_GUARD__', $topicGuard.Groups[1].Value).Replace('__SNAPSHOT__', $snapshot.Value)
$testFile = Join-Path $run 'LumaPersonalMessage80Test.java'
$vaultFixture = Join-Path $run 'BlackHoleVault.java'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($testFile, $testSource, $utf8)
[System.IO.File]::WriteAllText($vaultFixture, 'package org.telegram.messenger; public final class BlackHoleVault { public static void lockAll() {} }', $utf8)
$sources = @($testFile, $vaultFixture)
foreach ($fixture in @('android/content/Context', 'android/content/SharedPreferences', 'android/content/pm/PackageManager',
        'android/content/pm/PackageInfo', 'android/content/pm/Signature', 'android/app/Activity',
        'org/telegram/messenger/ApplicationLoader', 'org/telegram/messenger/UserConfig', 'org/telegram/messenger/BuildConfig')) {
    $sources += Join-Path $PSScriptRoot ('fixtures/' + $fixture + '.java')
}
foreach ($actual in @('LumaBuildPolicy', 'LumaAccountData', 'LumaDeletedMessages')) {
    $sources += Join-Path $repo ($javaRoot + 'messenger/' + $actual + '.java')
}
& (Join-Path $javaBin 'javac.exe') '-J-Xmx384m' -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Focused personal message regression compilation failed.' }
& (Join-Path $javaBin 'java.exe') '-Xmx384m' -cp $classes org.telegram.messenger.LumaPersonalMessage80Test
if ($LASTEXITCODE -ne 0) { throw 'Focused personal message regressions failed.' }
Write-Output "Test artifacts: $run"
