package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.config.MarketTimeZone;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.promo.CreatePromoCodeRequestDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeDTO;
import com.innbucks.bookingservice.dto.promo.PromoCodeTermsDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.PromoCode;
import com.innbucks.bookingservice.exception.ApiProblemException;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.repository.PromoCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The organizer's promo-code endpoints and the public check. */
class PromoCodeServiceTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private PromoCodeRepository codes;
    private BookingRepository bookings;
    private EventServiceClient events;
    private PromoCodeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        codes = mock(PromoCodeRepository.class);
        bookings = mock(BookingRepository.class);
        events = mock(EventServiceClient.class);
        ObjectProvider<EventServiceClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(events);
        when(events.getEventInternal(eq(eventId), anyString())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(owner).build()));
        when(codes.saveAndFlush(any())).thenAnswer(inv -> {
            PromoCode code = inv.getArgument(0);
            if (code.getId() == null) {
                code.setId(UUID.randomUUID());
            }
            return code;
        });
        service = new PromoCodeService(codes, bookings, provider, new MarketTimeZone("ZW"), "internal", null);
        service.setClock(Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC));
    }

    private CreatePromoCodeRequestDTO request(String code, PromoCode.DiscountType type, String value) {
        CreatePromoCodeRequestDTO request = new CreatePromoCodeRequestDTO();
        request.setEventId(eventId);
        request.setCode(code);
        request.setDiscountType(type);
        request.setDiscountValue(new BigDecimal(value));
        request.setEndsAt("2026-09-08T23:59:59");
        return request;
    }

    @Test
    void create_storesTheCodeUpperCased_withAUtcWindow_andWhoMadeIt() {
        PromoCodeDTO created = service.create(request(" early10 ", PromoCode.DiscountType.PERCENT, "10"), owner,
                owner.toString(), false);

        ArgumentCaptor<PromoCode> saved = ArgumentCaptor.forClass(PromoCode.class);
        verify(codes).saveAndFlush(saved.capture());
        assertEquals("EARLY10", saved.getValue().getCode());
        assertEquals(LocalDateTime.of(2026, 9, 8, 21, 59, 59), saved.getValue().getEndsAt());
        assertEquals(owner.toString(), saved.getValue().getCreatedBy());
        assertEquals("EARLY10", created.getCode());
        assertEquals(0L, created.getUses());
    }

    @Test
    void create_refusesBadCodesAndTerms_beforeAskingForOwnership() {
        assertThrows(BadRequestException.class,
                () -> service.create(request("x!", PromoCode.DiscountType.PERCENT, "10"), owner, "o", false));
        BadRequestException free = assertThrows(BadRequestException.class,
                () -> service.create(request("FREE", PromoCode.DiscountType.PERCENT, "100"), owner, "o", false));
        assertEquals("A percentage discount must be below 100: a code can't make tickets free.", free.getMessage());
        CreatePromoCodeRequestDTO over = request("OVER", PromoCode.DiscountType.FIXED, "5");
        over.setEndsAt("2026-08-31T23:59:59");
        assertEquals("The end of the promo code must be in the future.",
                assertThrows(BadRequestException.class, () -> service.create(over, owner, "o", false)).getMessage());

        verifyNoInteractions(events);
        verify(codes, never()).saveAndFlush(any());
    }

    @Test
    void create_aDuplicateCode_isAConflict() {
        when(codes.existsByEventIdAndCode(eventId, "EARLY10")).thenReturn(true);

        ApiProblemException ex = assertThrows(ApiProblemException.class,
                () -> service.create(request("early10", PromoCode.DiscountType.PERCENT, "10"), owner, "o", false));

        assertEquals("PROMO_CODE_EXISTS", ex.getErrorCode());
    }

    @Test
    void anotherOrganizer_cannotCreateListOrEdit() {
        UUID stranger = UUID.randomUUID();
        PromoCode stored = PromoCode.builder().id(UUID.randomUUID()).eventId(eventId).code("EARLY10")
                .discountType(PromoCode.DiscountType.PERCENT).discountValue(new BigDecimal("10.00")).build();
        when(codes.findById(stored.getId())).thenReturn(Optional.of(stored));
        PromoCodeTermsDTO terms = new PromoCodeTermsDTO();
        terms.setDiscountType(PromoCode.DiscountType.FIXED);
        terms.setDiscountValue(new BigDecimal("5"));

        assertThrows(AccessDeniedException.class,
                () -> service.create(request("EARLY10", PromoCode.DiscountType.PERCENT, "10"), stranger, "s", false));
        assertThrows(AccessDeniedException.class, () -> service.list(eventId, stranger, false));
        assertThrows(AccessDeniedException.class, () -> service.update(stored.getId(), terms, stranger, "s", false));
        verify(codes, never()).saveAndFlush(any());
    }

    @Test
    void update_replacesTheTerms_butNeverTheCode() {
        PromoCode stored = PromoCode.builder().id(UUID.randomUUID()).eventId(eventId).code("EARLY10")
                .discountType(PromoCode.DiscountType.PERCENT).discountValue(new BigDecimal("10.00"))
                .maxUses(100).build();
        when(codes.findById(stored.getId())).thenReturn(Optional.of(stored));
        PromoCodeTermsDTO terms = new PromoCodeTermsDTO();
        terms.setDiscountType(PromoCode.DiscountType.FIXED);
        terms.setDiscountValue(new BigDecimal("5"));
        terms.setActive(false);

        PromoCodeDTO updated = service.update(stored.getId(), terms, owner, owner.toString(), false);

        assertEquals("EARLY10", updated.getCode());
        assertEquals("FIXED", updated.getDiscountType());
        assertEquals(new BigDecimal("5.00"), updated.getDiscountValue());
        assertNull(updated.getMaxUses());
        assertFalse(updated.isActive());
        assertEquals(owner.toString(), stored.getUpdatedBy());
    }

    @Test
    void check_answersAnUnknownAndASwitchedOffCodeAlike_andRefusesOneUsedUp() {
        PromoCode off = PromoCode.builder().id(UUID.randomUUID()).eventId(eventId).code("OFF").active(false)
                .discountType(PromoCode.DiscountType.PERCENT).discountValue(BigDecimal.TEN).build();
        PromoCode full = PromoCode.builder().id(UUID.randomUUID()).eventId(eventId).code("FULL").active(true)
                .discountType(PromoCode.DiscountType.PERCENT).discountValue(BigDecimal.TEN).maxUses(2).build();
        when(codes.findByEventIdAndCode(eventId, "OFF")).thenReturn(Optional.of(off));
        when(codes.findByEventIdAndCode(eventId, "FULL")).thenReturn(Optional.of(full));
        when(bookings.countByPromoCodeIdAndStatusNot(full.getId(), Booking.BookingStatus.CANCELLED)).thenReturn(2L);

        assertEquals("PROMO_CODE_INVALID",
                assertThrows(ApiProblemException.class, () -> service.check(eventId, "nope")).getErrorCode());
        assertEquals("PROMO_CODE_INVALID",
                assertThrows(ApiProblemException.class, () -> service.check(eventId, "off")).getErrorCode());
        assertEquals("PROMO_CODE_USED_UP",
                assertThrows(ApiProblemException.class, () -> service.check(eventId, "full")).getErrorCode());
    }

    @Test
    void list_carriesEachCodesUses_andPlatformStaffSkipTheOwnershipCheck() {
        PromoCode code = PromoCode.builder().id(UUID.randomUUID()).eventId(eventId).code("EARLY10")
                .discountType(PromoCode.DiscountType.PERCENT).discountValue(new BigDecimal("10.00")).build();
        when(codes.findByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(List.of(code));
        BookingRepository.PromoCodeUsage usage = mock(BookingRepository.PromoCodeUsage.class);
        when(usage.getPromoCodeId()).thenReturn(code.getId());
        when(usage.getUses()).thenReturn(3L);
        when(usage.getDiscountGiven()).thenReturn(new BigDecimal("30.00"));
        when(bookings.summarizePromoCodeUses(List.of(code.getId()), Booking.BookingStatus.CANCELLED))
                .thenReturn(List.of(usage));

        List<PromoCodeDTO> listed = service.list(eventId, null, true);

        assertEquals(3L, listed.get(0).getUses());
        assertEquals(new BigDecimal("30.00"), listed.get(0).getDiscountGiven());
        verifyNoInteractions(events);
    }
}
