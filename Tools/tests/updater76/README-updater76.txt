Lunagram updater .76 regression audit

Run with an existing JDK 17:
pwsh -NoProfile -File tools/tests/run-updater76-regressions.ps1 -JavaHome JDK_PATH -OutputRoot TEST_OUTPUT_PATH
The runner uses sequential bounded JVMs (-J-Xmx128m / -Xmx96m), not Gradle, device
installation, publishing, signing or any real-account/network action.
Use -Baseline to compile HEAD production files instead; expected failure is nonzero.

Measured current results:
RESULT updater76-controller 17 cases, 87 assertions, 0 failures.
RESULT updater76-http 13 cases, 38 assertions, 0 failures.
Total: 30 cases, 125 executed assertions, 0 failures.

Measured HEAD baseline (published .75):
4f4182699eba35c754a8688dcb78d84854ed4516
RESULT updater76-controller 17 cases, 45 assertions, 12 failures.
RESULT updater76-http 13 cases, 5 assertions, 13 failures.
Some transport baseline cases fail because the new opt-in API is absent. These are
failing test cases, not 25 independently discovered application bugs.

Controller proof:
The complete production LumaUpdaterController.java and LumaUpdateFiles.java are
compiled verbatim. Controlled fixtures cover exact official-source API-first request
and raw Accept header, bounded parallel transport settings, single empty/malformed
raw fallback, wrapper rejection, valid older/no-new-release results, custom HTTPS
source retention/no-official-fallback, relative archive resolution against the original
raw manifest source, forced-check joining, thirty-second end-to-end watchdog,
queued result/deadline/source changes, stale same-generation primary and duplicate
fallback responses, cancelled old transport, callback reentry and exception isolation.
Download-listener reentry is covered explicitly: the valid new release/last_check must
be persisted before invoking the cancellation listener, and a source reset made inside
that listener cannot be overwritten by old terminal code afterward.

Limits of controller proof:
The JSONObject fixture explicitly maps registered response strings to values; it is
NOT Android's actual JSON parser. Unknown/malformed strings trigger its parse-failure
contract. No Base64 decoder or live GitHub response is tested: API contract here is a
raw UTF8 JSON response. UI dispatch/deadlines are controlled clock/queue models,
not Android Looper scheduling, process death or real Preferences restart behavior.
No screen rendering, spinner/banner interaction or live channel propagation is proven.

Transport proof:
The complete production HttpGetTask.java is compiled verbatim in a separate classpath
with a small AsyncTask model. URL.openConnection is intercepted inside the test JVM
and returns controlled HttpURLConnection objects. InputStream, UTF8 decoding and byte
arrays are real Java implementations. Cases cover positive finite configured timeouts,
UTF8 with legacy newline joining, multibyte byte-cap boundaries, oversized body rejection,
strict non-2xx failures, preserved non-strict legacy error bodies, absent error body,
connection/read timeout exceptions, parallel executor identity, cancellation before and
during reads, suppression of late postExecute callbacks, and finally close/disconnect.

Limits of transport proof:
No real socket, TLS, DNS, redirect/proxy behavior, Android AsyncTask worker pool or
actual elapsed network timeout is measured. The model checks configured deadline
values/executor identity and exercises production exception/cancellation cleanup.
It does not establish that a specific ISP, region or phone can reach the public feed.

The older .73 runner retains its deliberately lightweight HTTP fixture. Minimal new
method compatibility there is not evidence of real timeout/cancel behavior; that proof
belongs to the separate transport tests above.
