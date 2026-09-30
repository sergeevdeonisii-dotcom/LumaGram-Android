package org.telegram.messenger;

/** Standalone regression tests; run with javac/java without an Android device. */
public final class LumaPresenceRequestStateTest {
    public static void main(String[] args) {
        retryAfterNetworkFailure(false);
        retryAfterNetworkFailure(true);
        staleResponseDoesNotCompleteReplacement();
        cancellationInvalidatesResponse();
        System.out.println("Presence regression tests passed (online/offline retries, stale callbacks, cancellation).");
    }

    private static void retryAfterNetworkFailure(boolean offline) {
        LumaPresenceRequestState state = new LumaPresenceRequestState();
        check(state.canSend(offline, 100), "initial request allowed");
        int request = state.begin(offline);
        check(!state.canSend(offline, 10000), "pending request must not be duplicated");
        check(state.complete(request, 100, false), "network failure handled");
        check(!state.canSend(offline, 5099), "retry must be throttled");
        check(state.canSend(offline, 5100), "retry must not get stuck after failure");
        request = state.begin(offline);
        check(state.complete(request, 5200, true), "retry can succeed");
        check(state.canSend(!offline, 5200), "opposite status allowed after success");
        check(!state.complete(request, 5300, false), "duplicate callback ignored");
    }

    private static void staleResponseDoesNotCompleteReplacement() {
        LumaPresenceRequestState state = new LumaPresenceRequestState();
        int online = state.begin(false);
        check(state.canSend(true, 100), "offline can replace pending online");
        int offline = state.begin(true);
        check(!state.complete(online, 100, true), "stale online response ignored");
        check(!state.canSend(true, 100), "replacement remains pending");
        check(state.complete(offline, 100, true), "current response accepted");
        int newOnline = state.begin(false);
        check(!state.complete(offline, 200, false), "stale offline error ignored");
        check(state.complete(newOnline, 200, true), "new online remains valid");
    }

    private static void cancellationInvalidatesResponse() {
        LumaPresenceRequestState state = new LumaPresenceRequestState();
        int request = state.begin(true);
        state.cancel();
        check(!state.complete(request, 100, true), "cancelled response ignored");
        check(state.canSend(false, 100), "online allowed immediately after disabling ghost");
        request = state.begin(false);
        state.complete(request, 100, false);
        state.cancel();
        check(state.canSend(true, 101), "explicit toggle resets old retry delay");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
