package com.innbucks.userservice.service;

import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.dto.RegisterRequestDTO;
import com.innbucks.userservice.entity.TenantProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.BusinessRegistrationException;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.HtmlSanitizer;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Adds a business to the InnBucks app account that already holds its phone.
 *
 * <p><b>Why.</b> A customer whose phone signed in to the InnBucks app has an
 * account on that number (a placeholder name, no email, no password anyone
 * knows). Registering a business with the same number used to be refused
 * ("Phone number already registered"), because registration only ever creates
 * a new account, and the service-request route needs an email and a portal
 * sign-in the account does not have. So an app customer could never become an
 * organizer or merchant. Found on staging, 2026-11-04.
 *
 * <p><b>How.</b> The registrant proves the phone with a code
 * ({@code POST /auth/register/phone-code}, then {@code POST /auth/register} with
 * {@code phoneCode}). Without the code, anyone who knows a customer's number
 * could attach a business, and an email for password resets, to their account.
 * With it, the SAME account gets the email, its name (only while it still has
 * the placeholder), the business details, and one PENDING service request per
 * service. No role and no product: approving a request is what grants them,
 * exactly as for any service request. Until then the account stays an ordinary
 * active customer, so the Super app keeps working. Making it inactive while it
 * waits, as a new registration is, would sign the customer out of the app.
 *
 * <p><b>Who can.</b> Only an account that is exactly a customer: roles
 * {@code [CUSTOMER]}, active, approved, no staff profile. Anything else on the
 * number answers "Phone number already registered", as before. So does a number
 * that is an InnBucks staff member's contact phone, even with no account on it:
 * staff never belong to a business (owner rule), and the answer is the same as
 * any taken number so it says nothing about who is staff.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessRegistrationService {

    static final String PLACEHOLDER_FIRST_NAME = "Customer";
    static final String PLACEHOLDER_LAST_NAME = "Pending";
    static final String ALREADY_REGISTERED = "Phone number already registered";
    private static final int REASON_MAX = 1000;

    private final UserRepository userRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final TenantProfileRepository tenantProfileRepository;
    private final ServiceRequestService serviceRequestService;
    private final OtpService otpService;
    private final AuditService auditService;

    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /** An account a business can be added to: exactly a customer, active, approved, never staff. */
    public boolean isCustomerOnly(User user) {
        return user != null
                && user.getRoles() != null
                && user.getRoles().equals(Set.of(User.Role.CUSTOMER.name()))
                && user.isActive()
                && user.isApproved()
                && !staffProfileRepository.existsById(user.getId());
    }

    /** True when {@code canonicalPhone} is any InnBucks staff member's contact phone. */
    public boolean isStaffContactPhone(String canonicalPhone) {
        return canonicalPhone != null
                && !staffProfileRepository.findAllByContactPhoneIn(List.of(canonicalPhone)).isEmpty();
    }

    /**
     * {@code POST /auth/register/phone-code}: sends the code that proves the
     * phone, but only to a number whose account can take a business and has no
     * business registration already waiting.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendCode(String rawPhone) {
        String phone = MsisdnValidator.normalizeToE164(rawPhone, deploymentCountry)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid phone number: " + rawPhone));
        if (isStaffContactPhone(phone)) {
            log.warn("Business registration code refused: a staff contact phone phone={}", MsisdnMasking.mask(phone));
            throw alreadyRegistered();
        }
        Optional<User> onPhone = userRepository.findByPhoneNumber(phone);
        if (onPhone.isEmpty()) {
            throw BusinessRegistrationException.phoneCodeNotNeeded();
        }
        User account = onPhone.get();
        if (!isCustomerOnly(account)) {
            log.info("Business registration code refused: the account is not a plain customer phone={}",
                    MsisdnMasking.mask(phone));
            throw alreadyRegistered();
        }
        if (serviceRequestService.hasPending(account.getId(), allBundles())) {
            throw BusinessRegistrationException.pending();
        }
        // No transaction open: OtpService commits the code, then sends.
        otpService.sendBusinessRegistrationCode(phone);
        log.info("Business registration code sent phone={}", MsisdnMasking.mask(phone));
    }

    /**
     * The upgrade itself, inside {@code AuthService.register}'s transaction. The
     * caller has already refused a roles list, a staff email domain, an email
     * held by another account, and has worked out the bundles.
     *
     * <p>Order: lock the account and check it again; every refusal that does not
     * need the code; then the code (which commits on its own, so a wrong guess
     * stays counted); then the writes; then the REQUIRED audit, last.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthResponseDTO addBusiness(User account, String canonicalPhone, RegisterRequestDTO request,
                                       Set<String> bundles) {
        User user = userRepository.lockById(account.getId())
                .filter(this::isCustomerOnly)
                .orElseThrow(BusinessRegistrationService::alreadyRegistered);
        if (serviceRequestService.hasPending(user.getId(), bundles)) {
            throw BusinessRegistrationException.pending();
        }
        String bpoNumber = request.isBusiness() ? HtmlSanitizer.stripAll(request.getBpoNumber()) : null;
        Optional<TenantProfile> ownProfile = tenantProfileRepository.findByUserId(user.getId());
        if (bpoNumber != null && !bpoNumber.isBlank()
                && tenantProfileRepository.existsByBpoNumber(bpoNumber)
                && ownProfile.map(p -> !bpoNumber.equals(p.getBpoNumber())).orElse(true)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BPO number already registered");
        }
        if (request.getPhoneCode() == null || request.getPhoneCode().isBlank()) {
            throw BusinessRegistrationException.phoneCodeRequired();
        }
        if (!otpService.verifyBusinessRegistrationCode(canonicalPhone, request.getPhoneCode())) {
            log.warn("Business registration refused: wrong or expired code phone={}", MsisdnMasking.mask(canonicalPhone));
            throw BusinessRegistrationException.phoneCodeInvalid();
        }

        user.setEmail(request.getEmail());
        if (PLACEHOLDER_FIRST_NAME.equals(user.getFirstName()) && PLACEHOLDER_LAST_NAME.equals(user.getLastName())) {
            // Only the placeholder an app sign-in wrote. A name the customer
            // gave at tier 2 is theirs and stays.
            user.setFirstName(HtmlSanitizer.stripAll(request.getFirstName()));
            user.setMiddleName(HtmlSanitizer.stripAll(request.getMiddleName()));
            user.setLastName(HtmlSanitizer.stripAll(request.getLastName()));
        }
        if (user.getCountry() == null || user.getCountry().isBlank()) {
            user.setCountry(HtmlSanitizer.stripAll(request.getCountry()));
        }
        if (request.isBusiness()) {
            user.setBusiness(true);
        }
        userRepository.save(user);

        String businessName = request.isBusiness() ? HtmlSanitizer.stripAll(request.getBusinessName()) : null;
        if (request.isBusiness()) {
            TenantProfile profile = ownProfile.orElseGet(() -> TenantProfile.builder().user(user).build());
            profile.setBusinessName(businessName);
            profile.setBusinessAddress(HtmlSanitizer.stripAll(request.getBusinessAddress()));
            profile.setBusinessEmail(request.getBusinessEmail());
            profile.setBpoNumber(bpoNumber);
            tenantProfileRepository.save(profile);
        }

        List<Long> requestIds = serviceRequestService.submitBusinessRegistration(user, bundles,
                reason(request, businessName, bpoNumber));
        userRepository.flush();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("services", List.copyOf(new TreeSet<>(bundles)));
        metadata.put("serviceRequestIds", requestIds);
        metadata.put("business", request.isBusiness());
        metadata.put("email", maskEmail(request.getEmail()));
        metadata.put("phone", MsisdnMasking.mask(canonicalPhone));
        auditService.recordRequired(AuditEventType.BUSINESS_ADDED_TO_EXISTING_ACCOUNT,
                String.valueOf(user.getUserUuid()), AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getUserUuid()), AuditService.TARGET_TYPE_USER,
                metadata, AuditContext.none());

        log.info("Business added to an existing app account userId={} services={} requests={}",
                user.getId(), bundles, requestIds);
        return AuthResponseDTO.builder()
                .email(user.getEmail())
                .roles(user.getRoles().stream().sorted().toList())
                .defaultServices(new ArrayList<>(new TreeSet<>(bundles)))
                .mfaRequired(false)
                .mustChangePassword(false)
                .addedToExistingAccount(true)
                .build();
    }

    /** What the SUPER_ADMIN reads in the service-request queue: the account itself is not on the users list yet. */
    static String reason(RegisterRequestDTO request, String businessName, String bpoNumber) {
        StringBuilder sb = new StringBuilder("Business registration on an existing InnBucks app account. ")
                .append("Name: ").append(HtmlSanitizer.stripAll(request.getFirstName())).append(' ')
                .append(HtmlSanitizer.stripAll(request.getLastName())).append(". Email: ").append(request.getEmail())
                .append('.');
        if (request.isBusiness()) {
            sb.append(" Business: ").append(businessName).append('.');
            if (bpoNumber != null && !bpoNumber.isBlank()) sb.append(" BPO: ").append(bpoNumber).append('.');
            String address = HtmlSanitizer.stripAll(request.getBusinessAddress());
            if (address != null && !address.isBlank()) sb.append(" Address: ").append(address).append('.');
        }
        String reason = sb.toString();
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX);
    }

    private static List<String> allBundles() {
        return com.innbucks.userservice.service.Services.ALL_BUNDLES;
    }

    static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "****";
        return email.charAt(0) + "****" + email.substring(at);
    }

    private static ResponseStatusException alreadyRegistered() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, ALREADY_REGISTERED);
    }
}
