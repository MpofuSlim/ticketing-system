package com.innbucks.userservice.controller;

import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.security.LoyaltySessionTokenIssuer;
import com.innbucks.userservice.security.MfaPolicy;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.BusinessRegistrationService;
import com.innbucks.userservice.service.CustomerService;
import com.innbucks.userservice.service.FederatedLoginService;
import com.innbucks.userservice.service.LoginRateLimiter;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.security.MfaTokenService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.PasswordResetService;
import com.innbucks.userservice.service.RefreshTokenService;
import com.innbucks.userservice.service.ServiceRequestService;
import com.innbucks.userservice.service.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire half of adding a business to an InnBucks app account: the envelopes
 * the Foundry portal's registration page branches on, through the real
 * controller, {@code AuthService}, {@code BusinessRegistrationService} and the
 * global handler.
 */
class BusinessRegistrationDispatchTest {

    private static final String PHONE = "+263779637565";

    private UserRepository users;
    private OtpService otp;
    private ServiceRequestService serviceRequests;
    private User appCustomer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        otp = mock(OtpService.class);
        serviceRequests = mock(ServiceRequestService.class);
        StaffProfileRepository staffProfiles = mock(StaffProfileRepository.class);
        TenantProfileRepository tenants = mock(TenantProfileRepository.class);
        when(tenants.findByUserId(any())).thenReturn(Optional.empty());
        when(staffProfiles.findAllByContactPhoneIn(any())).thenReturn(List.of());
        when(users.findAllByEmailIgnoreCase(anyString())).thenReturn(List.of());
        when(serviceRequests.submitBusinessRegistration(any(), anyCollection(), anyString())).thenReturn(List.of(501L));

        appCustomer = User.builder().id(32L).userUuid(UUID.randomUUID())
                .firstName("Customer").lastName("Pending").phoneNumber(PHONE)
                .roles(User.roleNames(User.Role.CUSTOMER)).active(true).approved(true).password("x").build();
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(appCustomer));
        when(users.existsByPhoneNumberAndHomeCountry(PHONE, "ZW")).thenReturn(true);
        when(users.lockById(32L)).thenReturn(Optional.of(appCustomer));

        BusinessRegistrationService businessRegistrations = new BusinessRegistrationService(users, staffProfiles,
                tenants, serviceRequests, otp, mock(AuditService.class));
        AuthService auth = new AuthService(users, tenants, mock(CustomerProfileRepository.class),
                mock(PasswordEncoder.class), mock(JwtUtil.class), mock(TokenRevocationService.class),
                mock(RefreshTokenService.class), mock(RefreshTokenRepository.class), mock(AuditService.class));
        ReflectionTestUtils.setField(auth, "businessRegistrations", businessRegistrations);

        AuthController controller = new AuthController(
                auth, mock(CustomerService.class), mock(TokenRevocationService.class),
                otp, mock(CellAffinityChecker.class), mock(JwtUtil.class),
                mock(LoyaltySessionTokenIssuer.class), mock(LoginRateLimiter.class), mock(AuditService.class),
                mock(PasswordResetService.class), mock(MfaService.class), mock(MfaTokenService.class),
                mock(MfaPolicy.class), users, mock(FederatedLoginService.class), businessRegistrations);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private ResultActions register(String phoneCode) throws Exception {
        String code = phoneCode == null ? "" : ",\"phoneCode\":\"" + phoneCode + "\"";
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Tariro","lastName":"Moyo","phoneNumber":"0779637565","email":"tariro@showtime.co.zw",
                 "country":"Zimbabwe","defaultServices":["ticketing"]%s}
                """.formatted(code)));
    }

    private ResultActions askForCode(String phone) throws Exception {
        return mvc.perform(post("/auth/register/phone-code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\"}"));
    }

    @Test
    void registeringWithoutACode_is409PhoneCodeRequired() throws Exception {
        register(null).andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"code":"409 CONFLICT",
                         "message":"This number already has an InnBucks app account. We can add your business to it: ask for a code, then send your registration again with the code.",
                         "data":{"errorCode":"phone_code_required"}}""", JsonCompareMode.STRICT));
    }

    @Test
    void aWrongCode_is400PhoneCodeInvalid() throws Exception {
        register("111111").andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"400 BAD_REQUEST",
                         "message":"That code is wrong or has expired. Ask for a new one.",
                         "data":{"errorCode":"phone_code_invalid"}}""", JsonCompareMode.STRICT));
    }

    @Test
    void aCodeThatIsNotSixDigits_isAValidationError() throws Exception {
        register("12ab").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.phoneCode").value("The code is the 6 digits we sent to your phone"));
    }

    @Test
    void theRightCode_is201_addedToTheExistingAccount() throws Exception {
        when(otp.verifyBusinessRegistrationCode(PHONE, "482913")).thenReturn(true);

        register("482913").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(AuthController.ADDED_TO_EXISTING_ACCOUNT_MESSAGE))
                .andExpect(jsonPath("$.data.addedToExistingAccount").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.defaultServices[0]").value("ticketing"))
                .andExpect(jsonPath("$.data.email").value("tariro@showtime.co.zw"));
    }

    @Test
    void theCodeEndpoint_sendsTheCode() throws Exception {
        askForCode("0779637565").andExpect(status().isOk())
                .andExpect(content().json("""
                        {"code":"200 OK","message":"We sent a code to your phone. It expires in 5 minutes.","data":null}
                        """, JsonCompareMode.STRICT));
        verify(otp).sendBusinessRegistrationCode(PHONE);
    }

    @Test
    void theCodeEndpoint_forANumberWithNoAccount_is409PhoneCodeNotNeeded() throws Exception {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());

        askForCode("0779637565").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("phone_code_not_needed"));
    }

    @Test
    void theCodeEndpoint_whenAlreadyWaiting_is409() throws Exception {
        when(serviceRequests.hasPending(eq(32L), anyCollection())).thenReturn(true);

        askForCode("0779637565").andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.errorCode").value("business_registration_pending"));
    }

    @Test
    void theCodeEndpoint_tooManyCodes_is429() throws Exception {
        doThrow(new OtpService.OtpRateLimitException("Too many OTP requests. Try again in 30 minute(s)."))
                .when(otp).sendBusinessRegistrationCode(PHONE);

        askForCode("0779637565").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many OTP requests. Try again in 30 minute(s)."));
    }
}
