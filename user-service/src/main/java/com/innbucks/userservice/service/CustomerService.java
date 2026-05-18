package com.innbucks.userservice.service;

import com.innbucks.userservice.client.DepositAccount;
import com.innbucks.userservice.client.OradianClient;
import com.innbucks.userservice.client.OradianClientException;
import com.innbucks.userservice.client.OradianCustomerRequest;
import com.innbucks.userservice.client.OradianCustomerResponse;
import com.innbucks.userservice.dto.*;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.Device;
import com.innbucks.userservice.entity.PendingRegistration;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.DeviceRepository;
import com.innbucks.userservice.repository.PendingRegistrationRepository;
import com.innbucks.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
    private final OradianClient oradianClient;

    /**
     * Tier 1 no longer creates a User or CustomerProfile. It stashes the phone + hashed password
     * in a pending_registrations row and fires an OTP. The account is materialised later by
     * {@link OtpService#verifyOtp} once the customer submits a valid code.
     */
    @Transactional
    public CustomerRegistrationResponseDTO registerTier1(CustomerTier1RegisterDTO request) {
        log.info("Customer tier 1 registration phone={}", request.getPhoneNumber());
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new RuntimeException("Phone number already registered");
        }

        // Replace any in-flight pending registration — lets users recover from a mistyped password.
        pendingRegistrationRepository.deleteByPhoneNumber(request.getPhoneNumber());
        pendingRegistrationRepository.flush();

        Instant now = Instant.now();
        PendingRegistration pending = PendingRegistration.builder()
                .phoneNumber(request.getPhoneNumber())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .createdAt(now)
                .expiresAt(now.plus(PENDING_REGISTRATION_TTL))
                .build();
        pendingRegistrationRepository.save(pending);

        otpService.sendOtp(request.getPhoneNumber());

        return CustomerRegistrationResponseDTO.builder()
                .phoneNumber(request.getPhoneNumber())
                .tier(1)
                .verified(false)
                .nextStep("Verify your phone at /auth/otp/verify to complete account creation, then proceed to /auth/customer/register/tier2")
                .build();
    }

    @Transactional
    public CustomerRegistrationResponseDTO registerTier2(CustomerTier2RegisterDTO request) {
        CustomerProfile profile = loadProfile(request.getMsisdn(), 1);

        // Compose a canonical fullName from the three structured fields. The
        // profile still keeps it as a denormalised column so ID-matching and
        // downstream KYC checks have one human-readable string to work against.
        String fullName = composeFullName(request.getFirstName(),
                request.getMiddleName(), request.getLastName());
        profile.setFullName(fullName);
        profile.setNationalId(request.getNationalId());
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
        user.setFirstName(request.getFirstName());
        user.setMiddleName(request.getMiddleName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        userRepository.save(user);

        // Mirror the registration into Oradian middleware via the S2S endpoint.
        // Failure throws OradianClientException, which rolls back the @Transactional
        // saves above so local state can't advance to tier 2 without an Oradian
        // Person+Client. GlobalExceptionHandler maps the exception to HTTP 502.
        //
        // Idempotency key MUST be stable per customer (User.id, set at tier-1
        // and never re-issued). True atomicity between Oradian and the local
        // DB isn't possible — there's always a window where Oradian commits
        // but the local transaction rolls back (DB outage during commit, an
        // exception between the response and the final save, etc.). A stable
        // key lets a retry replay the Oradian call: within Oradian middleware's
        // 24h idempotency window the same key returns the cached response,
        // so we can stamp the existing externalID / clientID locally instead
        // of orphaning the Oradian record. Previously this key was freshly
        // randomised per call (UUID.randomUUID()) which defeated the entire
        // mechanism — every retry looked like a brand-new request to the
        // middleware.
        String idempotencyKey = "customer-tier-2:" + user.getId();
        OradianCustomerResponse oradian = oradianClient.createCustomer(
                toOradianRequest(request), idempotencyKey);
        if (oradian == null) {
            // Defensive: RestClient.body() can return null on an empty 200. We treat
            // that as a contract violation — the same as Oradian rejecting us — so
            // the @Transactional rolls back and the caller gets 502 from
            // GlobalExceptionHandler.
            throw new OradianClientException("Oradian middleware returned an empty response body");
        }

        // Stamp the Oradian linkage on the local profile so subsequent reads
        // (balance enquiries, account-status lookups, reconciliation jobs) can
        // hit Oradian by the stored externalID / clientID instead of querying
        // by msisdn each time.
        profile.setOradianExternalId(oradian.getOradianExternalId());
        profile.setOradianClientId(oradian.getOradianClientId());
        customerProfileRepository.save(profile);

        log.info("Tier-2 mirrored to Oradian phone={} userId={} oradianClientId={} externalId={}",
                user.getPhoneNumber(),
                user.getId(),
                oradian.getOradianClientId(),
                oradian.getOradianExternalId());

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
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phoneNumber));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Customer profile not found"));

        int currentTier = profile.getRegistrationTier();
        Integer nextTier = currentTier < MAX_TIER ? currentTier + 1 : null;

        return CustomerTierResponseDTO.builder()
                .phoneNumber(user.getPhoneNumber())
                .email(user.getEmail())
                .currentTier(currentTier)
                .nextTier(nextTier)
                .build();
    }

    /**
     * Customer-self deposits lookup. Verifies the JWT-derived phone resolves
     * to a real CUSTOMER row, then delegates to OradianClient which calls
     * Oradian middleware's S2S /internal/customers/{msisdn}/deposits and
     * returns just the deposits array. Keeps a stale JWT issued for a
     * de-registered customer from leaking Oradian data.
     */
    @Transactional(readOnly = true)
    public List<DepositAccount> getDepositsForCustomer(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phoneNumber));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        return oradianClient.getDeposits(phoneNumber);
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
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phoneNumber));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        // Capture the recipient's structured name once and stamp it onto every
        // returned account row so the sender's UI can show "Sending to: Jane M.
        // Doe" without a second lookup.
        return oradianClient.getDeposits(phoneNumber).stream()
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

    private static OradianCustomerRequest toOradianRequest(CustomerTier2RegisterDTO request) {
        // Oradian middleware's Gender enum only knows MALE / FEMALE; OTHER would be
        // rejected at JSON deserialisation. Fail fast here with a readable message
        // instead of relaying Oradian's 400.
        if (request.getGender() == CustomerProfile.Gender.OTHER) {
            throw new OradianClientException(
                    "Oradian middleware does not yet support gender=OTHER. Use MALE or FEMALE.");
        }
        CustomerTier2RegisterDTO.Address addr = request.getAddress();
        return OradianCustomerRequest.builder()
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .gender(request.getGender().name())
                .msisdn(request.getMsisdn())
                .nationalId(request.getNationalId())
                .email(request.getEmail())
                .address(OradianCustomerRequest.Address.builder()
                        .street1(addr.getStreet1())
                        .city(addr.getCity())
                        .postCode(addr.getPostCode())
                        .country(addr.getCountry())
                        .build())
                .clientCustomFields(request.getClientCustomFields())
                .build();
    }

    private CustomerProfile loadProfile(String phoneNumber, int requiredCurrentTier) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("Customer not found for phone " + phoneNumber));
        if (!user.hasRole(User.Role.CUSTOMER)) {
            throw new RuntimeException("User is not a customer");
        }
        CustomerProfile profile = customerProfileRepository.findByUserId(user.getId())
                .orElseThrow(() -> new RuntimeException("Customer profile not found"));
        if (profile.getRegistrationTier() < requiredCurrentTier) {
            throw new RuntimeException("Customer must complete tier " + requiredCurrentTier + " first");
        }
        return profile;
    }

}
