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

IME .75 compatibility follow-up (2026-10-07)
-------------------------------------------
The 27-case figures above are historical pre-publish .74 audit results. The baseline
for this follow-up is the released .74 production code at
263d93c8b1df2ad9fc1673b381f61e0153da03c4, not the older pre-fix HEAD.

Released .74 baseline, exact runner result:
RESULT 33 cases, 11373 assertions, 5 failures.

Current .75 candidate with the narrow IME-decoration allowlist, exact runner result:
RESULT 33 cases, 11468 assertions, 0 failures.

Six added cases cover:
 - composing UnderlineSpan present before afterTextChanged, for Latin and Cyrillic;
 - composing underline arriving after the watcher, before the first draw;
 - ordinary SuggestionSpan on pure committed words, with plain/easy/autocorrect flags;
 - late spelling SuggestionSpan on an already animated word;
 - CharacterStyle.wrap(composing underline), where the SPAN_COMPOSING flag belongs
   to the wrapper but the decoration type comes from getUnderlying();
 - explicit non-composing underline, wrapped explicit underline, composing rich
   StyleSpan/wrapped StyleSpan and ReplacementSpan still rendered natively.

Five animation-preservation cases fail on released .74 and pass on the candidate;
the negative rich/user-style protection case passes on both. This is five failing
test cases, not a claim of five independent bugs. The regression arose because .74
treated every CharacterStyle as user formatting, including ordinary IME decoration.

The added tests drive production watcher and draw methods with the spans present at
the relevant callback boundary. They also check that decoration bounds/flags remain
unchanged and animation transparency is removed after expiry/clear and skipped in
model draft copies. No keyboard features or spans are deleted to force animation.

Additional primary references for the IME contracts:
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/inputmethod/BaseInputConnection.java
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/text/style/SuggestionSpan.java
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/text/style/UnderlineSpan.java

Proof limits remain unchanged: the added SuggestionSpan/UnderlineSpan/wrapper classes
model their type, flags and underlying identity, not Android's underline rendering,
candidate selection UI, real Parcel round-trip or Samsung/Gboard InputConnection.
Passing these tests proves the narrow production classification/lifecycle fix under
the platform model. It does not prove the animation visually works on the owner's
phone, or that every Android keyboard uses these exact decoration spans.
