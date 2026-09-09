package com.innbucks.userservice.notification;

import com.innbucks.userservice.event.ServiceRequestDecided;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Pins the copy a requester actually receives. The reason an admin typed is the
 * whole point of the reject flow — a refusal that does not say why is what makes
 * people re-submit the identical request.
 */
class ServiceRequestDecisionListenerTest {

    private static final String WHY = "Your business verification is still outstanding.";

    @Test
    void aRejectionCarriesTheReviewersReason() {
        String body = deliver(decided(ServiceRequestDecided.Outcome.REJECTED, WHY));

        assertTrue(body.contains(WHY), body);
        assertTrue(body.contains("not approved"), body);
    }

    @Test
    void aRejectionSaysTheyMayApplyAgain() {
        // A rejection decides one request, not the bundle forever — the pending
        // uniqueness index is scoped to PENDING rows, so re-applying works.
        String body = deliver(decided(ServiceRequestDecided.Outcome.REJECTED, WHY));

        assertTrue(body.toLowerCase().contains("new request"), body);
    }

    @Test
    void anApprovalTellsThemToSignInAgain() {
        // The grant rides in the JWT, so it is invisible until a fresh token is
        // minted. Omitting this line is a guaranteed "it says approved but I
        // still cannot see it" support ticket.
        String body = deliver(decided(ServiceRequestDecided.Outcome.APPROVED, null));

        assertTrue(body.contains("sign in again"), body);
        assertTrue(body.contains("approved"), body);
    }

    @Test
    void theSubjectNamesTheBundle_soAnInboxIsScannable() {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        UserNotificationDispatcher dispatcher = mock(UserNotificationDispatcher.class);

        new ServiceRequestDecisionListener(dispatcher, mock(NotificationService.class))
                .onServiceRequestDecided(decided(ServiceRequestDecided.Outcome.REJECTED, WHY));

        verify(dispatcher).dispatch(any(), any(), subject.capture(), any());
        assertTrue(subject.getValue().contains("marketplace"), subject.getValue());
    }

    @Test
    void anApprovalWithNoReviewerNote_readsCleanly() {
        // decisionReason is optional on an approve; the copy must not render a
        // dangling "Note from the reviewer:" with nothing after it.
        String body = deliver(decided(ServiceRequestDecided.Outcome.APPROVED, null));

        assertFalse(body.contains("Note from the reviewer"), body);
    }

    @Test
    void contactDetailsArePassedThroughUntouched() {
        UserNotificationDispatcher dispatcher = mock(UserNotificationDispatcher.class);

        new ServiceRequestDecisionListener(dispatcher, mock(NotificationService.class))
                .onServiceRequestDecided(decided(ServiceRequestDecided.Outcome.REJECTED, WHY));

        // Channel selection + fallback is the dispatcher's job, not the
        // listener's — it just hands over both contacts.
        verify(dispatcher).dispatch(eq("alice@rudo.co.zw"), eq("+263771234567"), any(), any());
    }

    private static ServiceRequestDecided decided(ServiceRequestDecided.Outcome outcome, String reason) {
        return new ServiceRequestDecided(14L, 42L, java.util.UUID.randomUUID(),
                "alice@rudo.co.zw", "+263771234567",
                "marketplace", outcome, reason);
    }

    private static String deliver(ServiceRequestDecided event) {
        UserNotificationDispatcher dispatcher = mock(UserNotificationDispatcher.class);
        new ServiceRequestDecisionListener(dispatcher, mock(NotificationService.class)).onServiceRequestDecided(event);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).dispatch(any(), any(), any(), body.capture());
        return body.getValue();
    }
}
