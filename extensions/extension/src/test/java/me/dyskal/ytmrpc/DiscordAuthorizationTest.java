package me.dyskal.ytmrpc;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class DiscordAuthorizationTest {
    private static final String APP = "1559278040175878146";

    @Test public void duplicateTapsDoNotReplaceThePendingAttempt() throws Exception {
        DiscordAuthorization auth = new DiscordAuthorization();
        DiscordAuthorization.Attempt first = auth.begin(APP, 1000);
        assertNull(auth.begin(APP, 1001));
        assertSame(first, auth.claim(first.state, 1002));
        assertThrows(DiscordFailure.class, () -> auth.claim(first.state, 1003));
    }

    @Test public void cancellationRejectsAnAlreadyClaimedNetworkResult() throws Exception {
        DiscordAuthorization auth = new DiscordAuthorization();
        DiscordAuthorization.Attempt attempt = auth.begin(APP, 1000);
        auth.claim(attempt.state, 1001);
        assertSame(attempt, auth.cancel());
        assertFalse(auth.isCurrent(attempt)); assertFalse(auth.finish(attempt));
        DiscordAuthorization.Attempt next = auth.begin(APP, 1002);
        assertTrue(auth.isCurrent(next)); assertFalse(auth.finish(attempt));
    }

    @Test public void stateMismatchDoesNotConsumeTheLegitimateAttempt() throws Exception {
        DiscordAuthorization auth = new DiscordAuthorization();
        DiscordAuthorization.Attempt attempt = auth.begin(APP, 1000);
        assertThrows(DiscordFailure.class, () -> auth.claim("wrong-state", 1001));
        assertSame(attempt, auth.claim(attempt.state, 1002));
    }

    @Test public void pendingStateRoundTripsWithoutReplacingSavedCredentials() throws Exception {
        DiscordAuthorization auth = new DiscordAuthorization();
        DiscordAuthorization.Attempt attempt = auth.begin(APP, 1000);
        JSONObject saved = new JSONObject().put("access_token", "existing-grant").put("pending", attempt.toJson());
        assertEquals("existing-grant", saved.getString("access_token"));
        DiscordAuthorization restored = new DiscordAuthorization();
        DiscordAuthorization.Attempt next = restored.restore(saved.getJSONObject("pending"), 1001);
        assertNotNull(next); assertEquals(attempt.state, next.state); assertEquals(attempt.verifier, next.verifier);
        assertSame(next, restored.claim(attempt.state, 1002));
    }

    @Test public void expiredAttemptsAreNotRestoredOrAccepted() throws Exception {
        DiscordAuthorization auth = new DiscordAuthorization();
        DiscordAuthorization.Attempt attempt = auth.begin(APP, 1000);
        long expired = 1000 + DiscordAuthorization.TIMEOUT_MILLIS;
        assertThrows(DiscordFailure.class, () -> auth.claim(attempt.state, expired));
        assertNull(new DiscordAuthorization().restore(attempt.toJson(), expired));
    }

    @Test public void finishedCallbackCannotClaimOrConsumeANewPendingAttempt() throws Exception {
        DiscordAuthorization auth = new DiscordAuthorization();
        DiscordAuthorization.Attempt first = auth.begin(APP, 1000);
        assertSame(first, auth.claim(first.state, 1001)); assertTrue(auth.finish(first));
        assertThrows(DiscordFailure.class, () -> auth.claim(first.state, 1002));
        DiscordAuthorization.Attempt next = auth.begin(APP, 1003);
        assertThrows(DiscordFailure.class, () -> auth.claim(first.state, 1004));
        assertTrue(auth.isCurrent(next)); assertSame(next, auth.claim(next.state, 1005));
    }
}
