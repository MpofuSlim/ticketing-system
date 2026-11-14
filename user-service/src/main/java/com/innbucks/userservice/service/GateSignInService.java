package com.innbucks.userservice.service;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.dto.AuthResponseDTO;
import com.innbucks.userservice.entity.CustomerProfile;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GateSignInException;
import com.innbucks.userservice.integration.EventServiceClient;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.util.MsisdnMasking;
import com.innbucks.userservice.util.MsisdnValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * The Organizer app's gate sign-in (V57): a gate scanner signs in with their
 * phone number and an SMS code, and gets a session that can only scan.
 *
 * <p>Why not the customer OTP ({@code /auth/otp/*}): that mints a CUSTOMER
 * session, and a CUSTOMER session books tickets and, for a sales agent, sells
 * them for cash. An SMS code proves a phone and nothing else, so this session
 * carries only {@code GATE_SCANNER}: booking-service lets it scan, look a ticket
 * up and read its own scans, event-service lets it see its gate events and
 * decide its invites, and nothing else anywhere accepts it. Authority to scan is
 * still event-service's live team answer at every scan, exactly as for a Super
 * app scanner, so this sign-in grants no event by itself.
 *
 * <p><b>A code goes only to a number on a gate team</b> (event-service is asked,
 * with no transaction open). The answer to the caller is the same 200 for every
 * number, and the number's code quota is spent either way
 * ({@link OtpService#countGateSignInRequest}), so neither the answer nor a 429
 * says whether a number is on a team. Residuals, stated: a request that sends a
 * code takes as long as the SMS send, and an SMS outage turns only a team
 * number's request into a 502.
 *
 * <p><b>The account.</b> The session names the InnBucks account on the number,
 * whatever it holds, because event-service binds an accepted invite to that
 * account and booking-service asks about it at every scan: an invite accepted in
 * the Super app works here and the other way round. A number with no account
 * gets a customer account, shaped exactly as {@code /auth/exchange} creates one,
 * so the person's later Super app sign-in finds it. A deactivated account is
 * refused.
 */
@Service
@Slf4j
public class GateSignInService {

    public static final String CODE_SENT_MESSAGE =
            "If this number is on an event's gate team, we've sent it a sign-in code.";
    public static final String SIGNED_IN_MESSAGE = "Signed in for gate scanning";

    static final String CODE_METRIC = "user.gate_sign_in.code";
    static final String SIGN_IN_METRIC = "user.gate_sign_in.sign_in";

    private final boolean enabled;
    private final EventServiceClient eventService;
    private final OtpService otpService;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditService auditService;
    private final CellAffinityChecker cells;
    private final String deploymentCountry;
    private final Map<String, Counter> codeOutcomes;
    private final Map<String, Counter> signInOutcomes;
    private final SecureRandom random = new SecureRandom();

    /** See {@link TransactionPhases}; with none set (plain unit tests) a phase runs inline. */
    private TransactionTemplate tx;

    public GateSignInService(
            @Value("${auth.gate-sign-in.enabled:true}") boolean enabled,
            EventServiceClient eventService,
            OtpService otpService,
            UserRepository userRepository,
            CustomerProfileRepository customerProfileRepository,
            PasswordEncoder passwordEncoder,
            AuthService authService,
            AuditService auditService,
            CellAffinityChecker cells,
            @Value("${innbucks.country:ZW}") String deploymentCountry,
            MeterRegistry meters) {
        this.enabled = enabled;
        this.eventService = eventService;
        this.otpService = otpService;
        this.userRepository = userRepository;
        this.customerProfileRepository = customerProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.auditService = auditService;
        this.cells = cells;
        this.deploymentCountry = deploymentCountry;
        // Registered at 0, so the first sample of each is visible to increase().
        this.codeOutcomes = Map.of(
                "sent", counter(meters, CODE_METRIC, "sent"),
                "not_on_team", counter(meters, CODE_METRIC, "not_on_team"),
                "unavailable", counter(meters, CODE_METRIC, "unavailable"));
        this.signInOutcomes = Map.of(
                "signed_in", counter(meters, SIGN_IN_METRIC, "signed_in"),
                "rejected", counter(meters, SIGN_IN_METRIC, "rejected"));
    }

    private static Counter counter(MeterRegistry meters, String name, String outcome) {
        return Counter.builder(name).tag("outcome", outcome).register(meters);
    }

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * {@code POST /auth/gate/code}. Sends a code when the number is on a gate
     * team, and otherwise only counts the request; returns normally either way.
     *
     * @throws OtpService.OtpRateLimitException the number's quota is spent (429)
     * @throws GateSignInException 400 {@code invalid_phone}; 503 when event-service cannot answer
     */
    public void requestCode(String rawPhone) {
        requireEnabled();
        String phone = canonical(rawPhone).orElseThrow(GateSignInException::invalidPhone);
        cells.requireDomesticMsisdn(phone);

        // With no transaction open: a slow event-service holds no connection.
        Optional<Boolean> onTeam = eventService.onAGateTeam(phone);
        if (onTeam.isEmpty()) {
            codeOutcomes.get("unavailable").increment();
            log.warn("Gate sign-in code refused: event-service could not say whether phone={} is on a team",
                    MsisdnMasking.mask(phone));
            throw GateSignInException.unavailable();
        }
        if (onTeam.get()) {
            otpService.sendGateSignInCode(phone);
            codeOutcomes.get("sent").increment();
            log.info("Gate sign-in code sent phone={}", MsisdnMasking.mask(phone));
        } else {
            // Same quota as a real send, so a 429 says nothing about the number.
            otpService.countGateSignInRequest(phone);
            codeOutcomes.get("not_on_team").increment();
            log.info("Gate sign-in code not sent: phone={} is on no gate team", MsisdnMasking.mask(phone));
        }
    }

    /**
     * {@code POST /auth/gate/sign-in}. Uses the code up and mints a
     * GATE_SCANNER-only session for the account on the number.
     *
     * @throws GateSignInException 400 {@code code_invalid} (wrong, expired, never
     *         sent, or the account is deactivated: one answer), 400 {@code invalid_phone}
     */
    public AuthResponseDTO signIn(String rawPhone, String code, String deviceId, AuditContext auditContext) {
        requireEnabled();
        String phone = canonical(rawPhone).orElseThrow(GateSignInException::invalidPhone);
        cells.requireDomesticMsisdn(phone);

        // Committed on its own, so a wrong guess counts toward the three that
        // void the code whatever happens next.
        if (!otpService.verifyGateSignInCode(phone, code)) {
            throw reject("wrong_code", phone, auditContext);
        }
        try {
            return TransactionPhases.inTransaction(tx, () -> signInOnce(phone, deviceId, auditContext));
        } catch (CreateRaceLost raced) {
            // Two first sign-ins for one new number at once: the other created
            // the account. Use it, in a fresh transaction.
            log.info("Gate sign-in lost a create race for phone={}: using the existing row",
                    MsisdnMasking.mask(phone));
            try {
                return TransactionPhases.inTransaction(tx, () -> signInOnce(phone, deviceId, auditContext));
            } catch (CreateRaceLost again) {
                throw again.violation();
            }
        }
    }

    private AuthResponseDTO signInOnce(String phone, String deviceId, AuditContext auditContext) {
        Optional<User> existing = userRepository.findByPhoneNumber(phone);
        boolean created = existing.isEmpty();
        User user;
        if (existing.isPresent()) {
            user = existing.get();
            if (!user.isActive()) {
                throw reject("account_inactive", phone, auditContext);
            }
        } else {
            try {
                user = createCustomer(phone);
            } catch (DataIntegrityViolationException raced) {
                throw new CreateRaceLost(raced);
            }
        }
        AuthResponseDTO response = authService.issueGateScannerToken(user, deviceId);
        auditService.recordSuccess(
                AuditEventType.AUTH_GATE_SIGN_IN_SUCCESS,
                phone, AuditService.ACTOR_TYPE_USER,
                String.valueOf(user.getId()), AuditService.TARGET_TYPE_USER,
                Map.of("newAccount", created),
                auditContext);
        signInOutcomes.get("signed_in").increment();
        log.info("Gate sign-in phone={} userId={} newAccount={}", MsisdnMasking.mask(phone), user.getId(), created);
        return response;
    }

    /** Shaped exactly like {@code FederatedLoginService.createCustomer}: a tier-1 customer with no usable password. */
    private User createCustomer(String phone) {
        User user = User.builder()
                .firstName("Customer")
                .lastName("Pending")
                .phoneNumber(phone)
                // NOT NULL column, no chosen password: an unusable random hash.
                .password(passwordEncoder.encode(unusableSecret()))
                .roles(User.roleNames(User.Role.CUSTOMER))
                .mfaEnabled(false)
                .active(true)
                .approved(true)
                .homeCountry(cells.homeCountryFor(phone))
                .build();
        userRepository.save(user);
        userRepository.flush();
        customerProfileRepository.save(CustomerProfile.builder()
                .user(user)
                .registrationTier(1)
                .verified(false)
                .phoneVerified(true)
                .phoneVerifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
        log.info("Customer account created by gate sign-in phone={} userId={}", MsisdnMasking.mask(phone), user.getId());
        return user;
    }

    private Optional<String> canonical(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) return Optional.empty();
        return MsisdnValidator.normalizeToE164(rawPhone, deploymentCountry);
    }

    private void requireEnabled() {
        // Off = the endpoint does not exist.
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    private GateSignInException reject(String reason, String phone, AuditContext auditContext) {
        auditService.recordFailure(
                AuditEventType.AUTH_GATE_SIGN_IN_REJECTED,
                phone, AuditService.ACTOR_TYPE_USER,
                phone, AuditService.TARGET_TYPE_USER,
                reason, Map.of(), auditContext);
        signInOutcomes.get("rejected").increment();
        log.info("Gate sign-in refused phone={} reason={}", MsisdnMasking.mask(phone), reason);
        return GateSignInException.codeInvalid();
    }

    private String unusableSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Out of the sign-in transaction when a concurrent first sign-in created the account first. */
    private static final class CreateRaceLost extends RuntimeException {
        CreateRaceLost(DataIntegrityViolationException cause) {
            super(cause.getMessage(), cause);
        }

        DataIntegrityViolationException violation() {
            return (DataIntegrityViolationException) getCause();
        }
    }
}
