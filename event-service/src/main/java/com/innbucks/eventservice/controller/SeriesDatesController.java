package com.innbucks.eventservice.controller;

import com.innbucks.eventservice.dto.ApiResult;
import com.innbucks.eventservice.dto.series.SeriesMonthDTO;
import com.innbucks.eventservice.service.SeriesDatesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A series' dates for buyers, a month at a time (TIC-73). Public, like
 * {@code GET /events/{id}}: {@code SecurityConfig} permits this one path ahead
 * of the organizer's {@code /events/series/**}.
 */
@RestController
@RequestMapping("/events/series")
@Tag(name = "Event series", description = "Recurring events: one set-up, a date for every start in the booking "
        + "window. Set the template up with the ordinary event endpoints.")
public class SeriesDatesController {

    static final String MESSAGE = "Series dates retrieved";

    private final SeriesDatesService datesService;

    public SeriesDatesController(SeriesDatesService datesService) {
        this.datesService = datesService;
    }

    @GetMapping("/{seriesId}/dates")
    @Operation(summary = "A series' dates for buyers, by month (public)",
            description = """
                    For a date picker on Ticketize web and the Super app. No sign-in. An event carries its
                    seriesId; pass it here to show the other dates. Each date is an ordinary event: open it with
                    GET /events/{eventId} and book it with its eventId.

                    Lists the month's dates that are published and have not ended, soonest first, with the sale
                    status worked out now, the cheapest ticket (priceFrom) and a seats-left level. months lists
                    every month with such a date, for the picker's month list. Leave month out to get the month of
                    the next date.

                    The dates may be up to 30 seconds behind an organizer's change, and priceFrom and availability
                    up to about a minute behind sales. They are for display: the booking itself checks the stock
                    and the price.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The month",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Month", value = """
                                    {"code":"200 OK","message":"Series dates retrieved","data":{
                                      "seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f",
                                      "title":"Friday Comedy Night","venue":"Theatre in the Park, Harare",
                                      "month":"2026-12","months":["2026-12","2027-01"],
                                      "dates":[
                                        {"eventId":"7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
                                         "startDateTime":"2026-12-04T19:00:00+02:00","endDateTime":"2026-12-04T21:00:00+02:00",
                                         "salesStatus":"ON_SALE",
                                         "salesStartDateTime":"2026-11-20T19:00:00+02:00",
                                         "salesEndDateTime":"2026-12-04T18:30:00+02:00",
                                         "priceFrom":{"currentPrice":80.00,"fullPrice":120.00,"tierName":"Early bird",
                                           "categoryName":"Standard","soldOut":false},
                                         "availability":"FEW_LEFT"},
                                        {"eventId":"2b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091",
                                         "startDateTime":"2026-12-11T19:00:00+02:00","endDateTime":"2026-12-11T21:00:00+02:00",
                                         "salesStatus":"NOT_YET_ON_SALE",
                                         "salesStartDateTime":"2026-11-27T19:00:00+02:00",
                                         "salesEndDateTime":"2026-12-11T18:30:00+02:00",
                                         "priceFrom":{"currentPrice":120.00,"fullPrice":120.00,"tierName":null,
                                           "categoryName":"Standard","soldOut":false},
                                         "availability":"AVAILABLE"}]}}"""),
                            @ExampleObject(name = "No dates that month", value = """
                                    {"code":"200 OK","message":"Series dates retrieved","data":{
                                      "seriesId":"5d0c6f1e-3b2a-4c8d-9e7f-1a2b3c4d5e6f",
                                      "title":"Friday Comedy Night","venue":"Theatre in the Park, Harare",
                                      "month":"2027-05","months":["2026-12","2027-01"],"dates":[]}}""")})),
            @ApiResponse(responseCode = "400", description = "month is not like 2026-12",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Month",
                            value = """
                                    {"code":"400 BAD_REQUEST","message":"Validation failed","data":{
                                      "month":"Use a month like 2026-12."}}"""))),
            @ApiResponse(responseCode = "404", description = "No such series, one never published or taken down "
                    + "by staff, or series switched off in this cell",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Not found",
                                    value = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Series not found\",\"data\":null}"),
                            @ExampleObject(name = "Off",
                                    value = "{\"code\":\"404 NOT_FOUND\",\"message\":\"Not found\",\"data\":null}")}))
    })
    public ResponseEntity<ApiResult<SeriesMonthDTO>> dates(
            @Parameter(description = "Series id, from an event's seriesId") @PathVariable UUID seriesId,
            @Parameter(description = "The month, like 2026-12, in the market's calendar. Leave it out for the "
                    + "month of the next date.", example = "2026-12")
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(ApiResult.ok(MESSAGE, datesService.month(seriesId, month)));
    }
}
