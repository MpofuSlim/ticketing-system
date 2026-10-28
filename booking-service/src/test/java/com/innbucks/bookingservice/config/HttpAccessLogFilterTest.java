package com.innbucks.bookingservice.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access log never writes a full ticket number or phone number. Twilio
 * fetches {@code /bookings/{id}/tickets/{ticketNumber}/qr.png} for every
 * WhatsApp ticket, so this line used to log every ticket the service delivered.
 */
class HttpAccessLogFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpAccessLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void theQrFetch_isLoggedWithTheTicketNumberMasked() throws Exception {
        run("GET", "/bookings/1f0e9c3a-7b2d-4e5f-9a1b-2c3d4e5f6a7b/tickets/20261013-7KQ4M-9XZ2B/qr.png", null);

        List<String> lines = lines();
        assertThat(lines).hasSize(2);
        assertThat(lines).allMatch(l -> l.contains("/tickets/20261013-*****-**Z2B/qr.png"));
        assertThat(String.join("\n", lines)).doesNotContain("7KQ4M").doesNotContain("9XZ2B");
    }

    @Test
    void aPhoneLookup_isLoggedWithThePhoneMasked() throws Exception {
        run("GET", "/bookings/public/phone/+263771234567", "filter=UPCOMING");

        assertThat(lines()).allMatch(l -> l.contains("/bookings/public/phone/****4567?filter=UPCOMING"));
        assertThat(String.join("\n", lines())).doesNotContain("263771234567");
    }

    private void run(String method, String uri, String query) throws Exception {
        Filter filter = new HttpAccessLogFilter().httpAccessLogFilterRegistration().getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString(query);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
