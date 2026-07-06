package com.innbucks.userservice.service;

import com.innbucks.userservice.client.DepositAccount;
import com.innbucks.userservice.corebanking.CoreBankingCreateCustomerCommand;
import com.innbucks.userservice.corebanking.CoreBankingCustomerResult;
import com.innbucks.userservice.corebanking.CoreBankingPort;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.HtmlSanitizer;
import com.innbucks.userservice.util.MsisdnCountryResolver;
import com.innbucks.userservice.util.MsisdnValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    static final Duration PENDING_REGISTRATION_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final DeviceRepository deviceRepository;
    private final PendingRegistrationRepository pendingRegistrationRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    // Per-cell core-banking provider (Oradian for the Kenya cell, Veengu for
    // Zimbabwe once its adapter lands). Selected at deploy time via
    // innbucks.core-banking.provider — see CoreBankingPort.
    private final CoreBankingPort coreBanking;
    // Hashes the national ID before it lands in the DB (PII at rest). The raw
    // value still goes to core-banking straight off the request — only the
    // stored copy is HMAC'd.
    private final com.innbucks.userservice.security.NationalIdHasher nationalIdHasher;

    /** Deployment country fallback for MSISDNs whose dialling prefix isn't an
     *  InnBucks-market entry (rare; foreign numbers). Set via INNBUCKS_COUNTRY
     *  env var; same key the rest of the service is pinned to. */
    @Value("${innbucks.country:ZW}")
    private String deploymentCountry = "ZW";

    /**
     * A01/A04 — how recently the target phone must have completed OTP
     * verification for a tier2/3/4 KYC-upgrade to be allowed. The legitimate
     * flow (tier1 -> /auth/otp/verify -> tier2) stamps phone_verified_at moments
     * earlier, so 30 minutes is comfortably enough for a real user to fill in
     * the KYC form, while a stale/never-verified phone is refused. Field-injected
     * with a default (like {@link #deploymentCountry}) so it stays out of the
     * Lombok constructor and plain-{@code new} unit tests get a sane value.
     */
    @Value("${innbucks.registration.verify-window:PT30M}")
    private Duration verifyWindow = Duration.ofMinutes(30);

    /**
     * Tier 1 no longer creates a User or CustomerProfile. It stashes the phone + hashed password
     * in a pending_registrations row and fires an OTP. The account is materialised later by
     * {@link OtpService#verifyOtp} once the customer submits a valid code.
     */
    @Transactional
    public CustomerRegistrationResponseDTO registerTier1(CustomerTier1RegisterDTO request) {
        // Canonicalise to E.164 up front so the pending row, the OTP challenge,
        // and the eventual User row all key off one format (phone is the lookup
        // key across all three) and the admin console shows one consistent shape.
        String phone = normalizePhone(request.getPhoneNumber());
        log.info("Customer tier 1 registration phone={}", MsisdnMasking.mask(phone));
        // Resolve home_country from the now-canonical E.164 number (its country
        // code is guaranteed present); fall back to this cell's country. Matches
        // the uk_users_phone_country constraint tuple.
        String homeCountry = MsisdnCountryResolver.resolve(phone).orElse(deploymentCountry);
        if (userRepository.existsByPhoneNumberAndHomeCountry(phone, homeCountry)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number already registered");
        }

        // Replace any in-flight pending registration — lets users recover from a mistyped password.
        pendingRegistrationRepository.deleteByPhoneNumber(phone);
        pendingRegistrationRepository.flush();

        Instant now = Instant.now();
        PendingRegistration pending = PendingRegistration.builder()
                .phoneNumber(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .createdAt(now)
                .expiresAt(now.plus(PENDING_REGISTRATION_TTL))
                .build();
        pendingRegistrationRepository.save(pending);

        otpService.sendOtp(phone);

        return CustomerRegistrationResponseDTO.builder()
                .phoneNumber(phone)
                .tier(1)
                .verified(false)
                .nextStep("Verify your phone at /auth/otp/verify to complete account creation, then proceed to /auth/customer/register/tier2")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier2(CustomerTier2RegisterDTO request) {
        CustomerProfile profile = loadProfile(request.getMsisdn(), 1);
        requireRecentlyVerified(profile);

        // Strip any HTML from the free-text name fields before they land on the
        // persisted profile + user (OWASP A03 / stored-XSS). The raw request
        // values still flow to the core-banking create below unchanged, so the
        // outbound provider contract is preserved.
        String firstName = HtmlSanitizer.stripAll(request.getFirstName());
        String middleName = HtmlSanitizer.stripAll(request.getMiddleName());
        String lastName = HtmlSanitizer.stripAll(request.getLastName());

        // Compose a canonical fullName from the three structured fields. The
        // profile still keeps it as a denormalised column so ID-matching and
        // downstream KYC checks have one human-readable string to work against.
        String fullName = composeFullName(firstName, middleName, lastName);
        profile.setFullName(fullName);
        // HMAC the national ID before it touches the DB — it's PII and nothing
        // reads the stored value back (the core-banking create below uses the
        // raw value straight off the request). hash() is idempotent + null-safe.
        profile.setNationalId(nationalIdHasher.hash(request.getNationalId()));
        profile.setDateOfBirth(request.getDateOfBirth());
        profile.setGender(request.getGender());

        CustomerTier2RegisterDTO.Address addr = request.getAddress();
        profile.setAddress(com.innbucks.userservice.entity.CustomerProfileAddress.builder()
                .street1(addr.getStreet1())
                .city(addr.getCity())
                .postCode(addr.getPostCode())
                .country(addr.getCountry())
                .build());

        profile.setClientCustomFields(request.getClientCustomFields() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(request.getClientCustomFields()));
        profile.setRegistrationTier(2);
        customerProfileRepository.save(profile);

        // Mirror the structured fields onto the User row so the JWT issuer can
        // pick them up at next login (see AuthService.issueToken — tier>=2
        // CUSTOMERS get firstName/middleName/lastName claims).
        User user = profile.getUser();
        user.setFirstName(firstName);
        user.setMiddleName(middleName);
        user.setLastName(lastName);
        user.setEmail(request.getEmail());
        userRepository.save(user);

        // Mirror the registration into this cell's core-banking provider
        // (Oradian today; Veengu for the ZW cell once its adapter lands).
        // Failure throws the adapter's exception (e.g. OradianClientException),
        // which rolls back the @Transactional saves above so local state can't
        // advance to tier 2 without a core-banking record. GlobalExceptionHandler
        // maps the exception to HTTP 502.
        //
        // Idempotency key MUST be stable per customer (User.id, set at tier-1
        // and never re-issued). True atomicity between the provider and the
        // local DB isn't possible — there's always a window where the provider
        // commits but the local transaction rolls back (DB outage during
        // commit, an exception between the response and the final save, etc.).
        // A stable key lets a retry replay the create: within Oradian
        // middleware's 24h idempotency window the same key returns the cached
        // response, so we can stamp the existing externalID / clientID locally
        // instead of orphaning the provider-side record. Previously this key
        // was freshly randomised per call (UUID.randomUUID()) which defeated
        // the entire mechanism — every retry looked like a brand-new request.
        String idempotencyKey = "customer-tier-2:" + user.getId();
        CoreBankingCustomerResult coreBankingCustomer = coreBanking.createCustomer(
                toCreateCommand(request, user.getPhoneNumber()), idempotencyKey);

        // Stamp the core-banking linkage on the local profile so subsequent
        // reads (balance enquiries, account-status lookups, reconciliation
        // jobs) can hit the provider by the stored reference instead of
        // querying by msisdn each time. The provider-agnostic pair
        // (core_banking_provider, core_banking_profile_id) is the durable
        // linkage; the oradian_* columns are kept in lockstep for existing
        // tooling and stay null on non-Oradian cells.
        profile.setCoreBankingProvider(coreBanking.provider());
        profile.setCoreBankingProfileId(coreBankingCustomer.profileRef());
        profile.setOradianExternalId(coreBankingCustomer.oradianExternalId());
        profile.setOradianClientId(coreBankingCustomer.oradianClientId());
        customerProfileRepository.save(profile);

        log.info("Tier-2 mirrored to {} phone={} userId={} profileRef={} oradianClientId={}",
                coreBanking.provider(),
                MsisdnMasking.mask(user.getPhoneNumber()),
                user.getId(),
                coreBankingCustomer.profileRef(),
                coreBankingCustomer.oradianClientId());

        return CustomerRegistrationResponseDTO.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .tier(2)
                .verified(profile.isVerified())
                .nextStep("Submit biometrics and device registration at /auth/customer/register/tier3")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier3(String phoneNumber, CustomerTier3RegisterDTO request) {
        CustomerProfile profile = loadProfile(phoneNumber, 2);
        requireRecentlyVerified(profile);

        profile.setBiometricsReference(request.getBiometricsReference());
        profile.setRegistrationTier(3);
        customerProfileRepository.save(profile);

        User user = profile.getUser();
        DeviceRegistrationDTO deviceDto = request.getDevice();
        Device device = deviceRepository.findByUserIdAndDeviceId(user.getId(), deviceDto.getDeviceId())
                .orElseGet(() -> Device.builder().user(user).deviceId(deviceDto.getDeviceId()).build());
        device.setDeviceName(deviceDto.getDeviceName());
        device.setPlatform(deviceDto.getPlatform());
        device.setPushToken(deviceDto.getPushToken());
        deviceRepository.save(device);

        return CustomerRegistrationResponseDTO.builder()
                .userId(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .tier(3)
                .verified(profile.isVerified())
                .nextStep("Upload verification documents at /auth/customer/register/tier4")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier4(String phoneNumber, CustomerTier4RegisterDTO request) {
        CustomerProfile profile = loadProfile(phoneNumber, 3);
        requireRecentlyVerified(profile);

        profile.setIdDocumentPath(request.getIdDocumentPath());
        profile.setProofOfResidencePath(request.getProofOfResidencePath());
        profile.setPassportDocumentPath(request.getPassportDocumentPath());
        profile.setRegistrationTier(4);
        profile.setVerified(true);
        customerProfileRepository.save(profile);

        return CustomerRegistrationResponseDTO.builder()
                .userId(profile.getUser().getId())
                .phoneNumber(profile.getUser().getPhoneNumber())
                .tier(4)
                .verified(true)
                .nextStep(null)
                .build();
    }

    private static final int MAX_TIER = 4;

    @Transactional(readOnly = true)
    public CustomerTierResponseDTO getCustomerTierByPhoneNumber(String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        User user = userRepository.findByPhoneNumber(phone)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phone));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Customer profile not found"));

        int currentTier = profile.getRegistrationTier();
        Integer nextTier = currentTier < MAX_TIER ? currentTier + 1 : null;

        // OWASP A01: this response is served by the PUBLIC GET /auth/customer/tier
        // endpoint, so it must expose only the non-sensitive tier-progression
        // fields — never the customer's email (that would be an unauthenticated
        // phone -> email harvesting oracle). See CustomerTierResponseDTO.
        return CustomerTierResponseDTO.builder()
                .phoneNumber(user.getPhoneNumber())
                .currentTier(currentTier)
                .nextTier(nextTier)
                .build();
    }

    /**
     * Customer-self deposits lookup. Verifies the JWT-derived phone resolves
     * to a real CUSTOMER row, then delegates to this cell's core-banking
     * provider (Oradian: middleware's S2S /internal/customers/{msisdn}/deposits,
     * returning just the deposits array). Keeps a stale JWT issued for a
     * de-registered customer from leaking core-banking data.
     */
    @Transactional(readOnly = true)
    public List<DepositAccount> getDepositsForCustomer(String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        User user = userRepository.findByPhoneNumber(phone)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phone));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        return coreBanking.listDeposits(phone);
    }

    /**
     * Send-money projection: same upstream Oradian call as the deposits
     * lookup, but each row is reduced to identifying fields only — balance,
     * subscribed, and lifecycle dates are dropped. Use case: a sender
     * looking up a recipient by phone needs to pick the right account, not
     * see the recipient's private balance or account history.
     */
    @Transactional(readOnly = true)
    public List<CustomerSendMoneyDetail> getSendMoneyDetailsForCustomer(String phoneNumber) {
        String phone = normalizePhone(phoneNumber);
        User user = userRepository.findByPhoneNumber(phone)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phone));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        // Capture the recipient's structured name once and stamp it onto every
        // returned account row so the sender's UI can show "Sending to: Jane M.
        // Doe" without a second lookup.
        return coreBanking.listDeposits(phone).stream()
                .map(d -> toSendMoneyDetail(d, user))
                .toList();
    }

    private static CustomerSendMoneyDetail toSendMoneyDetail(DepositAccount d, User recipient) {
        return CustomerSendMoneyDetail.builder()
                .firstName(recipient.getFirstName())
                .middleName(recipient.getMiddleName())
                .lastName(recipient.getLastName())
                .internalID(d.getInternalID())
                .ID(d.getID())
                .externalAccountNumber(d.getExternalAccountNumber())
                .clientInternalID(d.getClientInternalID())
                .productID(d.getProductID())
                .productName(d.getProductName())
                .currencyCode(d.getCurrencyCode())
                .status(d.getStatus())
                .isMainAccount(d.getIsMainAccount())
                .isMessagingFeeAccount(d.getIsMessagingFeeAccount())
                .isJointAccount(d.getIsJointAccount())
                .build();
    }

    /** Joins first / middle / last into a single string, skipping any blanks. */
    private static String composeFullName(String first, String middle, String last) {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) sb.append(first.trim());
        if (middle != null && !middle.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(middle.trim());
        }
        if (last != null && !last.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last.trim());
        }
        return sb.toString();
    }

    /**
     * Provider-neutral create command. Provider-specific constraints (e.g.
     * Oradian's MALE/FEMALE-only gender enum) are enforced inside the
     * adapters, where the provider knowledge lives.
     */
    private static CoreBankingCreateCustomerCommand toCreateCommand(CustomerTier2RegisterDTO request,
                                                                    String normalizedMsisdn) {
        CustomerTier2RegisterDTO.Address addr = request.getAddress();
        return CoreBankingCreateCustomerCommand.builder()
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender().name())
                .msisdn(normalizedMsisdn)
                .nationalId(request.getNationalId())
                .email(request.getEmail())
                .address(CoreBankingCreateCustomerCommand.Address.builder()
                        .street1(addr.getStreet1())
                        .city(addr.getCity())
                        .postCode(addr.getPostCode())
                        .country(addr.getCountry())
                        .build())
                .clientCustomFields(request.getClientCustomFields())
                .build();
    }

    /**
     * Canonicalise a caller-supplied phone to E.164 ({@code +<cc><national>})
     * against this cell's country. Every phone that enters the service — being
     * stored OR used to look a customer up — passes through here, so writes and
     * reads always agree on one format (phone is the lookup key). An unparseable
     * number is rejected with 400 rather than reaching the DB or a lookup.
     */
    private String normalizePhone(String raw) {
        return MsisdnValidator.normalizeToE164(raw, deploymentCountry)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid phone number: " + raw));
    }

    /**
     * A01/A04 account-takeover guard for the tier2/3/4 KYC-upgrade endpoints.
     *
     * <p>Those endpoints take the target account's phone straight from the
     * request body and overwrite its email / KYC data. Without proof the caller
     * actually controls that phone, an unauthenticated attacker could upgrade
     * (and effectively take over) a victim's account simply by naming their
     * number. We require the phone to have completed an OTP verification within
     * {@link #verifyWindow} — the legitimate flow
     * (tier1 -> /auth/otp/verify -> tier2) stamps {@code phone_verified_at}
     * moments earlier, so it passes, while an attacker can't mint a fresh OTP
     * for a number they don't hold.
     *
     * <p>Rejected with 403 via {@link ResponseStatusException} (honoured by
     * {@code GlobalExceptionHandler.handleResponseStatus}). The request contract
     * is unchanged — the phone still comes from the body; the recency check is
     * the added gate, so there is no frontend change.
     */
    private void requireRecentlyVerified(CustomerProfile profile) {
        LocalDateTime verifiedAt = profile.getPhoneVerifiedAt();
        if (verifiedAt == null
                || Duration.between(verifiedAt, LocalDateTime.now(ZoneOffset.UTC))
                        .compareTo(verifyWindow) > 0) {
            // phone_verification_required — keep the copy generic (no account
            // enumeration) but actionable: point the user at the OTP step.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Verify your phone at /auth/otp/verify before completing registration.");
        }
    }

    private CustomerProfile loadProfile(String phoneNumber, int requiredCurrentTier) {
        // Normalise the lookup key to the same E.164 form registerTier1 stored,
        // so "0772...", "772..." and "+263772..." all resolve to the one row.
        String normalized = normalizePhone(phoneNumber);
        // Status codes stay 400 (not 404) on the "not found" branches even though
        // 404 is semantically purer — the existing IT contract
        // (AuthControllerIT.customerTier2_returns400WhenMsisdnDoesNotMatchAnyTier1)
        // pins 400, the FE branches on it, and the user's complaint was about the
        // generic MESSAGE catching this path, not the status code. The descriptive
        // reason still reaches the wire via GlobalExceptionHandler.handleResponseStatus.
        User user = userRepository.findByPhoneNumber(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No account found for this phone number."));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This account isn't a customer account.");
        }
        CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "We couldn't find your customer profile. Please contact support."));
        if (profile.getRegistrationTier() < requiredCurrentTier) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Please complete tier " + requiredCurrentTier + " registration first.");
        }
        return profile;
    }

}
