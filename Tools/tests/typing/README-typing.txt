Lunagram typing animation regression audit (.74 candidate)
2026-10-07

Run with an existing JDK 17:
pwsh -NoProfile -File tools/tests/run-typing-regressions.ps1 -JavaHome PATH_TO_JDK -OutputRoot TASK_TEST_OUTPUT
Use -Baseline to compile the production files from git HEAD instead. Its failures
are intentional reproductions, not a passing release gate. No Gradle, SDK download,
device installation, account traffic or repository mutation is performed by this runner.

The complete current production LumaTypingAnimator.java and LumaTextAnimation.java
are compiled verbatim. The standalone platform models are limited to deterministic
exclusive-span edits, clock and layout/canvas coordinates. They are NOT Android's
SpannableStringBuilder, IME, font shaper or renderer. Thus this is stronger than a
copy of classifier logic, but does not prove visual correctness on Galaxy S25.

Current results: 27 cases, 11325 assertions, 0 failures.
Git HEAD results: 27 cases, 1389 assertions, 15 failing cases (expected).
The HEAD baseline includes the previously fixed UTF-16 surrogate bug.

Confirmed fixes covered:
 - previous view/Editable spans are cleared on rebind, replacement and null-text clear;
 - transient transparency is a NoCopySpan and is not a Parcelable ForegroundColorSpan;
 - styled/ReplacementSpan, emoji/combining and contextual scripts stay native;
 - RTL paragraphs, unavailable layout and transformed text cannot stay concealed;
 - detached/hidden/disabled targets stop concealing text;
 - synthetic/reentrant watcher ranges cancel safely;
 - horizontal scrolling is not applied twice;
 - clip and visible-baseline tests use scroll-content coordinates, so later lines
   remain visible during multiline scrolling;
 - native text and selection are unchanged across draw/cancel/end cycles.

Other covered paths: adjacent characters, selection replacement, deletion, IME
commit/composition/autocorrection, whole-word/per-letter/no-swipe settings, multiline
and 100000-character paste, 1000 rapid single inserts (72 active spans maximum),
expiration/paint restoration, word wrapping, preference clamping/reset, and 2000
deterministic random edit/IME/layout sequences.

Explicit additional coverage: a pure IME commit in SWIPE_BY_LETTER produces five
individual glyph ranges and ten blur/sharp draw calls. Live tuning changes preserve
the old word's duration/lift/alpha at the same clock instant; later commits use the
new by-letter mode and faster duration. Disable/reenable restores native text without
resurrecting cancelled glyphs, then accepts subsequent input. These two additional
cases also pass on HEAD: they close coverage gaps, not newly discovered regressions.
IME begin/end and TextWatcher events are driven through the real production animator,
but this is not a real keyboard or an InputConnection/API 33 wrapper integration test.

Rendering tradeoff: plain Latin/Cyrillic/Greek BMP characters and ASCII symbols retain
the animation. Ranges that Canvas.drawText cannot reproduce faithfully are rendered
normally by Android, rather than briefly replacing them with malformed raw glyphs.
No arbitrary new style, font, animation duration or tuning default was introduced.

API compatibility: explicit BMP ranges avoid Character.UnicodeScript (Android API 24)
while this module declares minSdkVersion 21.

Primary implementation references inspected for the platform contract:
https://developer.android.com/reference/android/text/NoCopySpan
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/text/TextUtils.java
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/View.java
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/TextView.java

Still required before calling visual QA complete: build the APK, then check Samsung
IME/Gboard with fast Cyrillic typing, paste/replacement, selection, long multiline
scrolling, emoji+combining input, swipe commits, styling, close/reopen, orientation,
and a bot chat with Menu/Open alongside the composer. No claim of zero bugs is made.
