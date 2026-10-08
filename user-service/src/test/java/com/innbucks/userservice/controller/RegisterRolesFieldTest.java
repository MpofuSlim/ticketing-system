package com.innbucks.userservice.controller;

import com.innbucks.userservice.entity.User;
import com.innbucks.userservice.exception.GlobalExceptionHandler;
import com.innbucks.userservice.repository.CustomerProfileRepository;
import com.innbucks.userservice.repository.OrganizationMemberRepository;
import com.innbucks.userservice.repository.OrganizationRepository;
import com.innbucks.userservice.repository.RefreshTokenRepository;
import com.innbucks.userservice.repository.RoleRepository;
import com.innbucks.userservice.repository.StaffProfileRepository;
import com.innbucks.userservice.repository.TenantProfileRepository;
import com.innbucks.userservice.repository.UserRepository;
import com.innbucks.userservice.cells.CellAffinityChecker;
import com.innbucks.userservice.security.JwtUtil;
import com.innbucks.userservice.service.LoginRateLimiter;
import com.innbucks.userservice.security.MfaPolicy;
import com.innbucks.userservice.security.MfaTokenService;
import com.innbucks.userservice.service.TokenRevocationService;
import com.innbucks.userservice.service.AuditService;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.CustomerService;
import com.innbucks.userservice.service.FederatedLoginService;
import com.innbucks.userservice.security.LoyaltySessionTokenIssuer;
import com.innbucks.userservice.service.MfaService;
import com.innbucks.userservice.service.OtpService;
import com.innbucks.userservice.service.PasswordResetService;
import com.innbucks.userservice.service.RefreshTokenService;
import com.innbucks.userservice.service.RoleGrantGuard;
import com.innbucks.userservice.testsupport.InMemoryTokenVersionBumper;
import com.innbucks.userservice.testsupport.StaffFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /auth/register} (V44 §1.1): the {@code roles} field used to be
 * dropped silently — a console creating "staff" here got a business owner. It is
 * now refused when NON-EMPTY (400 {@code roles_not_accepted}); absent and
 * {@code []} are accepted exactly as before. A staff address is refused
 * (400 {@code email_domain_reserved}) BEFORE the duplicate-email check, so
 * registration is no oracle for which staff addresses exist.
 */
class RegisterRolesFieldTest {

    private UserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(4901L);
            return u;
        });
        AuthService auth = new AuthService(users, mock(TenantProfileRepository.class),
                mock(CustomerProfileRepository.class), encoder, mock(JwtUtil.class),
                mock(TokenRevocationService.class), mock(RefreshTokenService.class),
                mock(RefreshTokenRepository.class), mock(AuditService.class));
        ReflectionTestUtils.setField(auth, "tokenVersionBumper", new InMemoryTokenVersionBumper(null));
        RoleGrantGuard guard = new RoleGrantGuard(users, mock(RoleRepository.class));
        ReflectionTestUtils.setField(auth, "staffEligibility", StaffFixtures.eligibility(
                mock(StaffProfileRepository.class), guard, mock(OrganizationMemberRepository.class),
                mock(OrganizationRepository.class), mock(RoleRepository.class), users));

        AuthController controller = new AuthController(
                auth, mock(CustomerService.class), mock(TokenRevocationService.class),
                mock(OtpService.class), mock(CellAffinityChecker.class), mock(JwtUtil.class),
                mock(LoyaltySessionTokenIssuer.class), mock(LoginRateLimiter.class), mock(AuditService.class),
                mock(PasswordResetService.class), mock(MfaService.class), mock(MfaTokenService.class),
                mock(MfaPolicy.class), users, mock(FederatedLoginService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private ResultActions register(String email, String rolesJson) throws Exception {
        String roles = rolesJson == null ? "" : ",\"roles\":" + rolesJson;
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName":"Rudo","lastName":"Chikwanha","phoneNumber":"0777000001","email":"%s",
                 "country":"Zimbabwe","defaultServices":["loyalty"]%s}
                """.formatted(email, roles)));
    }

    @Test
    @DisplayName("roles absent: 201, as before")
    void absent() throws Exception {
        register("rudo@chikwanha-traders.co.zw", null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("roles []: 201, as before")
    void empty() throws Exception {
        register("rudo@chikwanha-traders.co.zw", "[]").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("roles non-empty: 400 roles_not_accepted, nothing written")
    void nonEmpty() throws Exception {
        register("rudo@chikwanha-traders.co.zw", "[\"PRODUCT_OFFICER\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Staff accounts are created by an administrator with POST /admin/staff."))
                .andExpect(jsonPath("$.data.errorCode").value("roles_not_accepted"))
                .andExpect(jsonPath("$.data.field").value("roles"));
        verify(users, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a staff address: 400 email_domain_reserved, decided BEFORE the duplicate check")
    void reservedBeforeDuplicate() throws Exception {
        when(users.existsByEmailIgnoreCase(anyString())).thenReturn(true);
        for (String email : new String[]{"tariro.moyo@innbucks.co.zw", "Tariro.Moyo@INNBUCKS.CO.KE",
                "someone@hr.innbucks.co.zw"}) {
            register(email, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            "InnBucks staff addresses can't be used here. Your administrator will invite you."))
                    .andExpect(jsonPath("$.data.errorCode").value("email_domain_reserved"));
        }
        verify(users, never()).existsByEmailIgnoreCase(anyString());
        verify(users, never()).save(any(User.class));

        // ...while a non-staff duplicate still gets the ordinary answer.
        register("rudo@chikwanha-traders.co.zw", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    @DisplayName("a letter-case variant of an address already held: 400 Email already registered (V49)")
    void caseVariantIsADuplicate() throws Exception {
        when(users.existsByEmailIgnoreCase("Rudo@Chikwanha-Traders.co.zw")).thenReturn(true);
        register("Rudo@Chikwanha-Traders.co.zw", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already registered"));
        verify(users, never()).save(any(User.class));
    }
}
