package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.service.TicketRenderingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the HTTP shape of the public ticket endpoints: PNG content-type for the
 * QR, HTML for the page, and 404 (not 500/empty-200) when the renderer returns
 * empty (unconfirmed/unknown booking or foreign ticket).
 */
class TicketControllerTest {

    private TicketRenderingService rendering;
    private TicketController controller;

    @BeforeEach
    void setUp() {
        rendering = mock(TicketRenderingService.class);
        controller = new TicketController(rendering);
        ReflectionTestUtils.setField(controller, "publicBaseUrl", "https://api.test");
    }

    @Test
    void qr_present_returnsPngBytes() {
        UUID id = UUID.randomUUID();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        when(rendering.ticketQrPng(id, "T1")).thenReturn(Optional.of(png));

        ResponseEntity<byte[]> resp = controller.ticketQr(id, "T1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(resp.getBody()).isEqualTo(png);
    }

    @Test
    void qr_empty_returns404() {
        UUID id = UUID.randomUUID();
        when(rendering.ticketQrPng(id, "T1")).thenReturn(Optional.empty());

        assertThat(controller.ticketQr(id, "T1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void page_present_returnsHtml() {
        UUID id = UUID.randomUUID();
        when(rendering.ticketPageHtml(id, "https://api.test")).thenReturn(Optional.of("<html>t</html>"));

        ResponseEntity<String> resp = controller.ticketPage(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(resp.getBody()).isEqualTo("<html>t</html>");
    }

    @Test
    void page_empty_returns404() {
        UUID id = UUID.randomUUID();
        when(rendering.ticketPageHtml(id, "https://api.test")).thenReturn(Optional.empty());

        assertThat(controller.ticketPage(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
