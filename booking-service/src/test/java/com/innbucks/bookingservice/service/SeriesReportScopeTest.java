package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.SeriesDateIdsDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.DependencyUnavailableException;
import com.innbucks.bookingservice.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Who may read a series report, and what an unanswered question does (TIC-72). */
class SeriesReportScopeTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID SERIES = UUID.randomUUID();
    private static final UUID DATE_1 = UUID.randomUUID();
    private static final UUID DATE_2 = UUID.randomUUID();

    private final EventServiceClient client = mock(EventServiceClient.class);
    private final SeriesReportScope scope = new SeriesReportScope(client, "token");

    private void answers(SeriesDateIdsDTO data) {
        when(client.seriesDates(eq(SERIES), eq("token")))
                .thenReturn(ApiResult.<SeriesDateIdsDTO>builder().code("200 OK").data(data).build());
    }

    private static SeriesDateIdsDTO found() {
        return new SeriesDateIdsDTO(SERIES, true, OWNER, List.of(DATE_1, DATE_2));
    }

    @Test
    void noSeries_isTheEventFilter_andAsksNobody() {
        UUID event = UUID.randomUUID();
        SeriesReportScope.Resolved r = scope.resolve(OWNER, event, null);
        assertThat(r.organizerUuid()).isEqualTo(OWNER);
        assertThat(r.events()).isEqualTo(ReportEvents.event(event));
        verifyNoInteractions(client);
    }

    @Test
    void theOwner_readsEveryDateOfTheirSeries() {
        answers(found());
        SeriesReportScope.Resolved r = scope.resolve(OWNER, null, SERIES);
        assertThat(r.organizerUuid()).isEqualTo(OWNER);
        assertThat(r.events().isSeries()).isTrue();
        assertThat(r.events().queryEventId()).isNull();
        assertThat(r.events().seriesEventIds()).isEqualTo(Set.of(DATE_1, DATE_2));
    }

    @Test
    void platformStaff_readAnySeries_scopedToItsOwner() {
        answers(found());
        assertThat(scope.resolve(null, null, SERIES).organizerUuid()).isEqualTo(OWNER);
    }

    @Test
    void anotherOrganizersSeries_isTheSame404AsAnUnknownOne() {
        answers(found());
        assertThatThrownBy(() -> scope.resolve(UUID.randomUUID(), null, SERIES))
                .isInstanceOf(NotFoundException.class).hasMessage(SeriesReportScope.SERIES_NOT_FOUND);

        answers(new SeriesDateIdsDTO(SERIES, false, null, List.of()));
        assertThatThrownBy(() -> scope.resolve(OWNER, null, SERIES))
                .isInstanceOf(NotFoundException.class).hasMessage(SeriesReportScope.SERIES_NOT_FOUND);
        assertThatThrownBy(() -> scope.resolve(null, null, SERIES))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void noAnswer_isA503_neverAReportOverNothing() {
        when(client.seriesDates(any(), any()))
                .thenReturn(ApiResult.<SeriesDateIdsDTO>builder().code("503").data(null).build());
        assertThatThrownBy(() -> scope.resolve(OWNER, null, SERIES))
                .isInstanceOf(DependencyUnavailableException.class);

        when(client.seriesDates(any(), any())).thenThrow(new RuntimeException("connection refused"));
        assertThatThrownBy(() -> scope.resolve(OWNER, null, SERIES))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void eventAndSeriesTogether_isA400_beforeAnyCall() {
        assertThatThrownBy(() -> scope.resolve(OWNER, DATE_1, SERIES))
                .isInstanceOf(BadRequestException.class).hasMessage(SeriesReportScope.EVENT_AND_SERIES);
        verifyNoInteractions(client);
    }
}
