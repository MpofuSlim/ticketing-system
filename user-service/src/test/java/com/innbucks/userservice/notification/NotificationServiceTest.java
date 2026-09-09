package com.innbucks.userservice.notification;

import com.innbucks.userservice.entity.Notification;
import com.innbucks.userservice.exception.NotFoundException;
import com.innbucks.userservice.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID SOMEONE_ELSE = UUID.randomUUID();

    private NotificationService service(NotificationRepository repo) {
        return new NotificationService(repo);
    }

    private static NotificationService.NewNotification request(String type) {
        return new NotificationService.NewNotification(
                ME, type, "Title", "Body", Notification.Severity.SUCCESS,
                "42", "Alice Moyo", "SERVICE_REQUEST", "14", "/x?highlight=14");
    }

    @Test
    void create_persistsTheWholeShape() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        service(repo).create(request(NotificationType.SERVICE_REQUEST_APPROVED));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(saved.capture());
        Notification n = saved.getValue();
        assertThat(n.getRecipientUuid()).isEqualTo(ME);
        assertThat(n.getType()).isEqualTo(NotificationType.SERVICE_REQUEST_APPROVED);
        assertThat(n.getSeverity()).isEqualTo(Notification.Severity.SUCCESS);
        assertThat(n.getSubjectKind()).isEqualTo("SERVICE_REQUEST");
        assertThat(n.getDeepLink()).isEqualTo("/x?highlight=14");
        assertThat(n.getCreatedAt()).isNotNull();
        assertThat(n.getReadAt()).as("a new notification is unread").isNull();
    }

    @Test
    void create_defaultsAnAbsentTypeRatherThanRejectingIt() {
        // Producers live in other services and repos. Losing a notification
        // because a field was blank would drop the message at the moment it
        // mattered.
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        service(repo).create(new NotificationService.NewNotification(
                ME, null, "T", "B", null, null, null, null, null, null));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.GENERAL);
        assertThat(saved.getValue().getSeverity()).isEqualTo(Notification.Severity.INFO);
    }

    @Test
    void create_keepsAnUnknownType_ratherThanCollapsingItToGeneral() {
        // A marketplace deploy that emits a type this service has not heard of
        // must still reach the bell — the client renders an unknown type with a
        // default icon, which beats losing it.
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        service(repo).create(request("SOME_FUTURE_TYPE"));

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo("SOME_FUTURE_TYPE");
    }

    @Test
    void markRead_stampsTheTime() {
        NotificationRepository repo = mock(NotificationRepository.class);
        UUID id = UUID.randomUUID();
        Notification n = Notification.builder().id(id).recipientUuid(ME).build();
        when(repo.findByIdAndRecipientUuid(id, ME)).thenReturn(Optional.of(n));

        service(repo).markRead(ME, id);

        assertThat(n.getReadAt()).isNotNull();
        verify(repo).save(n);
    }

    @Test
    void markRead_isIdempotent_andKeepsTheFirstReadTime() {
        // "When did they first see this" is the answer with any value; bumping
        // the timestamp on every re-read would destroy it.
        NotificationRepository repo = mock(NotificationRepository.class);
        UUID id = UUID.randomUUID();
        LocalDateTime firstRead = LocalDateTime.now(ZoneOffset.UTC).minusHours(3);
        Notification n = Notification.builder().id(id).recipientUuid(ME).readAt(firstRead).build();
        when(repo.findByIdAndRecipientUuid(id, ME)).thenReturn(Optional.of(n));

        service(repo).markRead(ME, id);

        assertThat(n.getReadAt()).isEqualTo(firstRead);
        verify(repo, never()).save(any());
    }

    @Test
    void markRead_cannotReachAnotherUsersNotification() {
        // The recipient is part of the QUERY, so someone else's notification is
        // indistinguishable from one that does not exist — no existence oracle.
        NotificationRepository repo = mock(NotificationRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndRecipientUuid(id, SOMEONE_ELSE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(repo).markRead(SOMEONE_ELSE, id))
                .isInstanceOf(NotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void unreadEtag_changesWhenSomethingArrives() {
        NotificationRepository repo = mock(NotificationRepository.class);
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 9, 8, 30);
        when(repo.latestCreatedAt(ME)).thenReturn(Optional.of(t1));
        String before = service(repo).unreadEtag(ME, 3);

        when(repo.latestCreatedAt(ME)).thenReturn(Optional.of(t1.plusMinutes(1)));
        String after = service(repo).unreadEtag(ME, 4);

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void unreadEtag_changesWhenSomethingIsRead_evenThoughNothingArrived() {
        // The timestamp alone would not move here. Pairing it with the count is
        // what stops a stale 304 hiding a badge that just went to zero.
        NotificationRepository repo = mock(NotificationRepository.class);
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 9, 8, 30);
        when(repo.latestCreatedAt(ME)).thenReturn(Optional.of(t1));

        assertThat(service(repo).unreadEtag(ME, 3))
                .isNotEqualTo(service(repo).unreadEtag(ME, 0));
    }

    @Test
    void unreadEtag_isStableWhenNothingChanged() {
        // The other half: identical state must produce an identical validator,
        // or every poll is a 200 and the ETag buys nothing.
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.latestCreatedAt(ME)).thenReturn(Optional.of(LocalDateTime.of(2026, 9, 9, 8, 30)));

        assertThat(service(repo).unreadEtag(ME, 3)).isEqualTo(service(repo).unreadEtag(ME, 3));
    }

    @Test
    void unreadEtag_handlesAnEmptyMailbox() {
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.latestCreatedAt(ME)).thenReturn(Optional.empty());

        assertThat(service(repo).unreadEtag(ME, 0)).isEqualTo("\"0-0\"");
    }

    @Test
    void markAllRead_isOneStatement_notAPageAndSaveLoop() {
        // An admin back from leave can have thousands unread; the
        // read-modify-write version is unbounded work inside their request.
        NotificationRepository repo = mock(NotificationRepository.class);
        when(repo.markAllRead(eq(ME), any())).thenReturn(7);

        assertThat(service(repo).markAllRead(ME)).isEqualTo(7);
        verify(repo).markAllRead(eq(ME), any());
        verify(repo, never()).save(any());
    }
}
