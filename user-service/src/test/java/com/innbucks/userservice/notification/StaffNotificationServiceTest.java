package com.innbucks.userservice.notification;

import com.innbucks.userservice.config.StaffAccountProperties;
import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The event-review staff notices (event-service V17): the audience comes from
 * the type, here; support never hears; every recipient gets a bell and an
 * email; a link that is not a console path is dropped.
 */
class StaffNotificationServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final UserNotificationDispatcher dispatcher = mock(UserNotificationDispatcher.class);
    private final StaffAccountProperties staff = mock(StaffAccountProperties.class);
    private final StaffNotificationService service =
            new StaffNotificationService(users, notifications, dispatcher, staff);

    private static User staffMember(String email) {
        return User.builder().userUuid(UUID.randomUUID()).email(email).password("x").build();
    }

    private static StaffNotificationService.StaffNotice notice(String type, String deepLink) {
        return new StaffNotificationService.StaffNotice(type, "Event awaiting approval: Pink Fun Run",
                "Harare Runners Club submitted \"Pink Fun Run\" for review.", "WARNING",
                "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", "Harare Runners Club",
                "EVENT", "4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11", deepLink);
    }

    @Test
    void eventNotices_reachTheReviewRoles_andNeverSupport() {
        assertThat(StaffNotificationService.AUDIENCES.get(NotificationType.EVENT_SUBMITTED))
                .containsExactlyInAnyOrder("SUPER_ADMIN", "PRODUCT_MANAGER", "PRODUCT_OFFICER");
        assertThat(StaffNotificationService.AUDIENCES.get(NotificationType.EVENT_PUBLISHED))
                .containsExactlyInAnyOrder("SUPER_ADMIN", "PRODUCT_MANAGER", "PRODUCT_OFFICER");
        assertThat(StaffNotificationService.AUDIENCES.values()).allSatisfy(roles -> assertThat(roles)
                .noneMatch(role -> role.startsWith("CALL_CENTER") || role.equals("FRAUD_DESK")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyActiveHolder_getsABell_andAnEmailWithTheConsoleLink() {
        User owner = staffMember("owner@innbucks.co.zw");
        User manager = staffMember("manager@innbucks.co.zw");
        when(users.findByActiveAndAnyRole(eq(true), any())).thenReturn(List.of(owner, manager));
        when(staff.consoleBaseUrl()).thenReturn("https://foundry.innbucks.co.zw");

        int reached = service.notifyStaff(notice(NotificationType.EVENT_SUBMITTED,
                "/ticketing/events/4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11"));

        assertThat(reached).isEqualTo(2);
        ArgumentCaptor<java.util.Collection<String>> roles = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(users).findByActiveAndAnyRole(eq(true), roles.capture());
        assertThat(roles.getValue()).containsExactlyInAnyOrder("SUPER_ADMIN", "PRODUCT_MANAGER", "PRODUCT_OFFICER");

        ArgumentCaptor<NotificationService.NewNotification> bell =
                ArgumentCaptor.forClass(NotificationService.NewNotification.class);
        verify(notifications, times(2)).create(bell.capture());
        NotificationService.NewNotification first = bell.getAllValues().get(0);
        assertThat(first.recipientUuid()).isEqualTo(owner.getUserUuid());
        assertThat(first.type()).isEqualTo("EVENT_SUBMITTED");
        assertThat(first.severity()).isEqualTo(Notification.Severity.WARNING);
        assertThat(first.subjectKind()).isEqualTo("EVENT");
        assertThat(first.deepLink()).isEqualTo("/ticketing/events/4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11");
        assertThat(first.body()).doesNotContain("foundry.innbucks.co.zw");

        verify(dispatcher).dispatch(eq("owner@innbucks.co.zw"), any(), eq("Event awaiting approval: Pink Fun Run"),
                eq("Harare Runners Club submitted \"Pink Fun Run\" for review.\n\nOpen it in the Foundry portal: "
                        + "https://foundry.innbucks.co.zw/ticketing/events/4b6d1f0e-2c8a-4e7b-9a31-7f5e2d9c0a11"));
        verify(dispatcher).dispatch(eq("manager@innbucks.co.zw"), any(), anyString(), anyString());
    }

    @Test
    void aLinkThatIsNotAConsolePath_isDropped_fromTheBellAndTheEmail() {
        when(users.findByActiveAndAnyRole(eq(true), any())).thenReturn(List.of(staffMember("a@innbucks.co.zw")));
        when(staff.consoleBaseUrl()).thenReturn("https://foundry.innbucks.co.zw");

        for (String link : List.of("https://evil.example/x", "//evil.example/x", "/events\nInjected: yes",
                "javascript:alert(1)")) {
            service.notifyStaff(notice(NotificationType.EVENT_PUBLISHED, link));
        }

        ArgumentCaptor<NotificationService.NewNotification> bell =
                ArgumentCaptor.forClass(NotificationService.NewNotification.class);
        verify(notifications, times(4)).create(bell.capture());
        assertThat(bell.getAllValues()).allSatisfy(n -> assertThat(n.deepLink()).isNull());
        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(dispatcher, times(4)).dispatch(any(), any(), any(), mailed.capture());
        assertThat(mailed.getAllValues()).allSatisfy(body -> assertThat(body).doesNotContain("Foundry portal"));
    }

    @Test
    void noConsoleAddress_mailsTheMessageAlone() {
        when(users.findByActiveAndAnyRole(eq(true), any())).thenReturn(List.of(staffMember("a@innbucks.co.zw")));
        when(staff.consoleBaseUrl()).thenReturn("");

        service.notifyStaff(notice(NotificationType.EVENT_PUBLISHED, "/ticketing/events/1"));

        verify(dispatcher).dispatch(any(), any(), any(),
                eq("Harare Runners Club submitted \"Pink Fun Run\" for review."));
    }

    @Test
    void oneRecipientFailing_doesNotStopTheOthers() {
        User broken = staffMember("broken@innbucks.co.zw");
        User fine = staffMember("fine@innbucks.co.zw");
        when(users.findByActiveAndAnyRole(eq(true), any())).thenReturn(List.of(broken, fine));
        when(notifications.create(any())).thenAnswer(inv -> {
            NotificationService.NewNotification n = inv.getArgument(0);
            if (n.recipientUuid().equals(broken.getUserUuid())) {
                throw new IllegalStateException("bell down");
            }
            return null;
        });

        assertThat(service.notifyStaff(notice(NotificationType.EVENT_SUBMITTED, null))).isEqualTo(1);
        verify(dispatcher).dispatch(eq("fine@innbucks.co.zw"), any(), any(), any());
        verify(dispatcher, never()).dispatch(eq("broken@innbucks.co.zw"), any(), any(), any());
    }

    @Test
    void aTypeWithNoAudience_isRefused_andReachesNobody() {
        assertThat(StaffNotificationService.knownType("GENERAL")).isFalse();
        assertThat(StaffNotificationService.knownType(null)).isFalse();
        assertThatThrownBy(() -> service.notifyStaff(notice("GENERAL", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(users, notifications, dispatcher);
    }
}
