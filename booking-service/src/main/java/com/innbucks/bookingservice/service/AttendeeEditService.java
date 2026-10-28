package com.innbucks.bookingservice.service;

import com.innbucks.common.ticket.TicketNumbers;
import com.innbucks.bookingservice.dto.AttendeeUpdateRequestDTO;
import com.innbucks.bookingservice.dto.AttendeeUpdateResponseDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.exception.NotFoundException;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.util.MsisdnMasking;
import com.innbucks.bookingservice.util.MsisdnValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Corrects one ticket's named attendee after booking — the console's answer to
 * "the guest's number was typed wrong". Sends nothing: the resend button
 * re-delivers to the corrected contact.
 *
 * <p>Rules: same ownership as the resend (SUPER_ADMIN / PRODUCT_MANAGER any
 * booking, an EVENT_ORGANIZER only its own events; PRODUCT_OFFICER is read-only); a CANCELLED booking and a ticket
 * already used at the gate are refused; the phone is validated and stored in
 * E.164 exactly as at booking. The booking row is locked first, as every other
 * booking write does. Every edit is logged with the actor, the ticket, the
 * fields changed and the phones MASKED.
 */
@Service
@Slf4j
public class AttendeeEditService {

    private final BookingRepository bookingRepository;
    private final String deploymentCountry;

    public AttendeeEditService(BookingRepository bookingRepository,
                               @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.bookingRepository = bookingRepository;
        this.deploymentCountry = deploymentCountry;
    }

    @Transactional
    public AttendeeUpdateResponseDTO update(Authentication caller, UUID bookingId, String ticketNumber,
                                            AttendeeUpdateRequestDTO request) {
        Booking booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        requireOwnershipUnlessStaff(caller, booking);

        BookingItem item = booking.getItems() == null ? null : booking.getItems().stream()
                .filter(i -> ticketNumber.equals(i.getTicketNumber()))
                .findFirst().orElse(null);
        if (item == null) {
            throw new NotFoundException("Ticket not found on this booking");
        }
        if (booking.getStatus() == Booking.BookingStatus.CANCELLED) {
            throw new BadRequestException("A cancelled booking's attendees cannot be changed");
        }
        if (item.getRedeemedAt() != null) {
            throw new BadRequestException("This ticket has already been used at the gate");
        }

        String name = trimToNull(request.fullName());
        if (name == null) {
            throw new BadRequestException("Attendee full name is required");
        }
        String email = trimToNull(request.email());
        String phone = trimToNull(request.phoneNumber());
        if (phone != null) {
            phone = MsisdnValidator.normalizeToE164(phone, deploymentCountry)
                    .orElseThrow(() -> new BadRequestException(
                            "That phone number doesn't look valid. Please enter it in full international "
                                    + "format, e.g. +263772000000."));
        }

        List<String> changed = new ArrayList<>();
        if (!Objects.equals(name, item.getAttendeeName())) changed.add("fullName");
        if (!Objects.equals(email, item.getAttendeeEmail())) changed.add("email");
        String oldPhone = item.getAttendeePhone();
        if (!Objects.equals(phone, oldPhone)) changed.add("phoneNumber");

        if (!changed.isEmpty()) {
            item.setAttendeeName(name);
            item.setAttendeeEmail(email);
            item.setAttendeePhone(phone);
            log.info("Attendee edited bookingId={} ref={} ticket={} by={} changed={} phone {} -> {}",
                    booking.getId(), booking.getConfirmationNumber(), TicketNumbers.forLog(ticketNumber), caller.getName(),
                    changed, MsisdnMasking.mask(oldPhone), MsisdnMasking.mask(phone));
        }

        return new AttendeeUpdateResponseDTO(booking.getId(), booking.getConfirmationNumber(),
                item.getTicketNumber(), item.getAttendeeName(), item.getAttendeeEmail(),
                item.getAttendeePhone(), item.holderName(), List.copyOf(changed));
    }

    private static void requireOwnershipUnlessStaff(Authentication caller, Booking booking) {
        // Explicit roles, not AuthenticatedCaller.isPlatformStaff: that set is
        // read scope and includes the read-only PRODUCT_OFFICER.
        boolean staff = caller.getAuthorities().stream().anyMatch(a ->
                "ROLE_SUPER_ADMIN".equals(a.getAuthority()) || "ROLE_PRODUCT_MANAGER".equals(a.getAuthority()));
        if (staff) {
            return;
        }
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(caller);
        if (organizerUuid == null || booking.getTenantUserUuid() == null
                || !organizerUuid.equals(booking.getTenantUserUuid())) {
            throw new AccessDeniedException("You do not own this booking's event");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
