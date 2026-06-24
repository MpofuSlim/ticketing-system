package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceProperties;
import com.innbucks.bookingservice.entity.EventInvoice;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.repository.EventInvoiceRepository;
import com.innbucks.bookingservice.service.InvoiceGenerator.EventRevenueLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The money-correctness heart of invoicing: commission rounded per line then
 * summed, VAT on the commission, idempotency, and the zero-billable skip.
 */
class InvoiceGeneratorTest {

    private EventInvoiceRepository invoices;
    private BillingConfigService billingConfig;
    private InvoiceProperties properties;
    private InvoiceGenerator generator;

    private final UUID organizer = UUID.randomUUID();
    private final LocalDate start = LocalDate.of(2026, 5, 1);
    private final LocalDate end = LocalDate.of(2026, 5, 31);

    @BeforeEach
    void setUp() {
        invoices = mock(EventInvoiceRepository.class);
        billingConfig = mock(BillingConfigService.class);
        properties = new InvoiceProperties(); // real defaults: vat 15.0, dueDays 14
        generator = new InvoiceGenerator(invoices, billingConfig, properties);

        when(invoices.nextInvoiceNumberValue()).thenReturn(42L);
        when(invoices.saveAndFlush(any(EventInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(billingConfig.resolve(organizer)).thenReturn(terms(new BigDecimal("10.0"), "USD"));
    }

    @Test
    void generate_computesCommissionPerLineThenTaxOnTheSum() {
        when(invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizer, start, end)).thenReturn(false);

        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        List<EventRevenueLine> lines = List.of(
                new EventRevenueLine(e1, 2, 3, new BigDecimal("150.00")),
                new EventRevenueLine(e2, 1, 3, new BigDecimal("300.00")));

        Optional<EventInvoice> result = generator.generate(organizer, start, end, lines);

        assertThat(result).isPresent();
        EventInvoice inv = result.get();
        // gross 450; commission = 15.00 + 30.00 = 45.00; tax = 45 * 15% = 6.75; total = 51.75
        assertThat(inv.getGrossSales()).isEqualByComparingTo("450.00");
        assertThat(inv.getCommissionRate()).isEqualByComparingTo("10.0");
        assertThat(inv.getCommissionAmount()).isEqualByComparingTo("45.00");
        assertThat(inv.getTaxRate()).isEqualByComparingTo("15.0");
        assertThat(inv.getTaxAmount()).isEqualByComparingTo("6.75");
        assertThat(inv.getTotalAmount()).isEqualByComparingTo("51.75");
        assertThat(inv.getConfirmedBookings()).isEqualTo(3);
        assertThat(inv.getTicketsSold()).isEqualTo(6);
        assertThat(inv.getStatus()).isEqualTo(EventInvoice.InvoiceStatus.ISSUED);
        assertThat(inv.getInvoiceNumber()).isEqualTo("INV-2026-000042");
        assertThat(inv.getDueAt()).isEqualTo(inv.getIssuedAt().plusDays(14));
        // Line items sum to the header commission, to the penny.
        assertThat(inv.getLineItems()).hasSize(2);
        BigDecimal lineSum = inv.getLineItems().stream()
                .map(li -> li.getCommissionAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lineSum).isEqualByComparingTo(inv.getCommissionAmount());
    }

    @Test
    void generate_isIdempotent_skipsWhenInvoiceAlreadyExists() {
        when(invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizer, start, end)).thenReturn(true);

        Optional<EventInvoice> result = generator.generate(organizer, start, end,
                List.of(new EventRevenueLine(UUID.randomUUID(), 1, 1, new BigDecimal("100.00"))));

        assertThat(result).isEmpty();
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void generate_skipsWhenCommissionIsZero() {
        when(invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizer, start, end)).thenReturn(false);
        when(billingConfig.resolve(organizer)).thenReturn(terms(BigDecimal.ZERO, "USD"));

        Optional<EventInvoice> result = generator.generate(organizer, start, end,
                List.of(new EventRevenueLine(UUID.randomUUID(), 5, 9, new BigDecimal("999.00"))));

        assertThat(result).isEmpty();
        verify(invoices, never()).saveAndFlush(any());
    }

    @Test
    void generate_usesTheResolvedCurrency() {
        when(invoices.existsByOrganizerUuidAndPeriodStartAndPeriodEnd(organizer, start, end)).thenReturn(false);
        when(billingConfig.resolve(organizer)).thenReturn(terms(new BigDecimal("10.0"), "KES"));

        EventInvoice inv = generator.generate(organizer, start, end,
                List.of(new EventRevenueLine(UUID.randomUUID(), 1, 1, new BigDecimal("100.00")))).orElseThrow();

        assertThat(inv.getCurrency()).isEqualTo("KES");
    }

    private OrganizerBillingConfig terms(BigDecimal rate, String currency) {
        OrganizerBillingConfig c = new OrganizerBillingConfig();
        c.setOrganizerUuid(organizer);
        c.setCommissionRate(rate);
        c.setBillingCycle(BillingCycle.MONTHLY);
        c.setCurrency(currency);
        return c;
    }
}
