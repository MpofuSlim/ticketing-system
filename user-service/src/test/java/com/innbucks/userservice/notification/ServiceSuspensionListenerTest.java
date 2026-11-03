package com.innbucks.userservice.notification;

import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.entity.OrganizationMember;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.service.ServiceSuspensionService.ServiceSuspensionChanged;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The business's owners and admins hear about a suspension and a reinstate
 * (V53): active ones only, in-app and by email, in plain ASCII (the
 * notification API refuses anything else in a subject).
 */
class ServiceSuspensionListenerTest {

    private static final UUID ORG = UUID.randomUUID();

    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final UserNotificationDispatcher dispatcher = mock(UserNotificationDispatcher.class);
    private final ServiceSuspensionListener listener =
            new ServiceSuspensionListener(members, users, notifications, dispatcher);

    @Test
    void theWording_namesTheServiceTheBusinessAndTheReason_inPlainAscii() {
        ServiceSuspensionChanged suspended = new ServiceSuspensionChanged(ORG, "Moyo Fresh Foods", "loyalty", true,
                "Approved by mistake (OPS-1204).");
        assertThat(ServiceSuspensionListener.subject(suspended))
                .isEqualTo("Your Loyalty service on InnBucks has been suspended");
        assertThat(ServiceSuspensionListener.message(suspended)).isEqualTo("Hello,\n\nThe Loyalty service for "
                + "Moyo Fresh Foods on InnBucks has been suspended, so your team can't use it until it is reinstated. "
                + "You have been signed out.\n\nReason: Approved by mistake (OPS-1204).\n\n"
                + "If you think this is a mistake, please contact InnBucks support.");

        ServiceSuspensionChanged back = new ServiceSuspensionChanged(ORG, "Moyo Fresh Foods", "ticketing", false, null);
        assertThat(ServiceSuspensionListener.subject(back)).isEqualTo("Your Ticketing service on InnBucks is back on");
        assertThat(ServiceSuspensionListener.message(back)).contains("has been reinstated. Sign out and sign in again");

        for (String text : List.of(ServiceSuspensionListener.subject(suspended),
                ServiceSuspensionListener.message(suspended), ServiceSuspensionListener.message(back))) {
            assertThat(text.chars().allMatch(c -> c < 128)).as(text).isTrue();
        }
    }

    @Test
    void activeOwnersAndAdmins_getABellAndAnEmail_anInactiveOneNothing() {
        User owner = User.builder().id(1L).userUuid(UUID.randomUUID()).email("rudo@moyo.example").password("x")
                .active(true).build();
        User gone = User.builder().id(2L).userUuid(UUID.randomUUID()).email("left@moyo.example").password("x")
                .active(false).build();
        when(members.findByOrganizationIdAndRoleIn(eq(ORG), any())).thenReturn(List.of(
                OrganizationMember.builder().organizationId(ORG).userId(1L).role(OrganizationMember.Role.OWNER).build(),
                OrganizationMember.builder().organizationId(ORG).userId(2L).role(OrganizationMember.Role.ADMIN).build()));
        when(users.findAllById(List.of(1L, 2L))).thenReturn(List.of(owner, gone));

        listener.onChanged(new ServiceSuspensionChanged(ORG, "Moyo Fresh Foods", "marketplace", true, "Mistake."));

        ArgumentCaptor<NotificationService.NewNotification> bell =
                ArgumentCaptor.forClass(NotificationService.NewNotification.class);
        verify(notifications).create(bell.capture());
        assertThat(bell.getValue().recipientUuid()).isEqualTo(owner.getUserUuid());
        assertThat(bell.getValue().type()).isEqualTo(NotificationType.SERVICE_SUSPENDED);
        assertThat(bell.getValue().severity()).isEqualTo(Notification.Severity.WARNING);
        assertThat(bell.getValue().subjectId()).isEqualTo(ORG.toString());
        verify(dispatcher).dispatch(eq("rudo@moyo.example"), any(), any(), any());
        verify(dispatcher, never()).dispatch(eq("left@moyo.example"), any(), any(), any());
    }
}
