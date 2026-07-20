package com.innbucks.userservice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean-validation contract for {@link RegisterRequestDTO} — the merchant/organizer
 * registration payload (POST /auth/register). Pure JUnit + a standalone Validator
 * (no Spring context, no Docker), so it runs in milliseconds and pins exactly the
 * rules the QA script exercises. Row numbers below reference that script.
 */
class RegisterRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** A fully-valid business registration to mutate per-case. */
    private RegisterRequestDTO valid() {
        RegisterRequestDTO r = new RegisterRequestDTO();
        r.setFirstName("Alice");
        r.setMiddleName("Jane");
        r.setLastName("Moyo");
        r.setPhoneNumber("+263771234567");
        r.setEmail("alice@example.com");
        r.setCountry("Zimbabwe");
        r.setDefaultServices(List.of("loyalty"));
        r.setBusiness(true);
        r.setBusinessName("Doe Enterprises Ltd");
        r.setBusinessAddress("12 Allen Avenue, Ikeja, Lagos");
        r.setBusinessEmail("accounts@example.com");
        r.setBpoNumber("12345678-0001");
        return r;
    }

    private Set<String> violatingFields(RegisterRequestDTO r) {
        return validator.validate(r).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void validPayload_hasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    // ── First / Last name character rules (rows 5, 6, 16, 17) ──────────────

    @Test
    void firstName_withDigits_isRejected() {
        RegisterRequestDTO r = valid();
        r.setFirstName("John123");
        assertThat(violatingFields(r)).contains("firstName");
    }

    @Test
    void lastName_withSymbols_isRejected() {
        RegisterRequestDTO r = valid();
        r.setLastName("John@#$");
        assertThat(violatingFields(r)).contains("lastName");
    }

    @Test
    void middleName_withInvalidChars_isRejected() {
        RegisterRequestDTO r = valid();
        r.setMiddleName("Mich@el123");
        assertThat(violatingFields(r)).contains("middleName");
    }

    // ── Valid name shapes stay valid (rows 7, 8, 11, 24) ──────────────────

    @Test
    void names_allowHyphenApostropheUnicodeAndSingleChar() {
        RegisterRequestDTO r = valid();
        r.setFirstName("Anne-Marie");
        r.setMiddleName("O'Brien");
        r.setLastName("José");
        assertThat(validator.validate(r)).isEmpty();

        r.setFirstName("李雷");   // non-Latin script (i18n)
        r.setLastName("J");        // single character
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void middleName_isOptional() {
        RegisterRequestDTO r = valid();
        r.setMiddleName(null);
        assertThat(validator.validate(r)).isEmpty();
    }

    // ── Name length: max 50 (rows 9, 10, 20) ──────────────────────────────

    @Test
    void firstName_atMaxLength_isAccepted_andBeyond_isRejected() {
        RegisterRequestDTO r = valid();
        r.setFirstName("A".repeat(50));
        assertThat(validator.validate(r)).isEmpty();

        r.setFirstName("A".repeat(51));
        assertThat(violatingFields(r)).contains("firstName");
    }

    // ── Email format + length (rows 29–33, 36, 37) ────────────────────────

    @Test
    void email_missingAtSymbol_isRejected() {
        RegisterRequestDTO r = valid();
        r.setEmail("john.doeexample.com");
        assertThat(violatingFields(r)).contains("email");
    }

    @Test
    void email_missingTld_isRejected() {
        RegisterRequestDTO r = valid();
        r.setEmail("john@example");   // @Email alone would accept this
        assertThat(violatingFields(r)).contains("email");
    }

    @Test
    void email_doubleAt_isRejected() {
        RegisterRequestDTO r = valid();
        r.setEmail("john@@example.com");
        assertThat(violatingFields(r)).contains("email");
    }

    @Test
    void email_withSpace_isRejected() {
        RegisterRequestDTO r = valid();
        r.setEmail("john doe@example.com");
        assertThat(violatingFields(r)).contains("email");
    }

    @Test
    void email_exceedingMaxLength_isRejected() {
        RegisterRequestDTO r = valid();
        // Format-valid but 260 chars: local part 64 (the RFC 5321 max @Email
        // itself enforces) + three 63-char domain labels + ".com" -> trips the
        // @Size(max = 254) cap, not @Email.
        String email = "a".repeat(64) + "@"
                + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";
        r.setEmail(email);
        assertThat(email.length()).isGreaterThan(254);
        assertThat(violatingFields(r)).contains("email");
    }

    // ── BPO / TIN format + length (rows 62, 64, 65, 68) ───────────────────

    @Test
    void bpo_validDigitsAndHyphen_isAccepted() {
        assertThat(validator.validate(valid())).isEmpty();   // valid() uses 12345678-0001
    }

    @Test
    void bpo_alphabetic_isRejected() {
        RegisterRequestDTO r = valid();
        r.setBpoNumber("ABCDEFGH");
        assertThat(violatingFields(r)).contains("bpoNumber");
    }

    @Test
    void bpo_withSymbols_isRejected() {
        RegisterRequestDTO r = valid();
        r.setBpoNumber("1234@5678");
        assertThat(violatingFields(r)).contains("bpoNumber");
    }

    @Test
    void bpo_tooShort_isRejected() {
        RegisterRequestDTO r = valid();
        r.setBpoNumber("123");
        assertThat(violatingFields(r)).contains("bpoNumber");
    }

    // ── Required fields (rows 2, 13, 27) ──────────────────────────────────

    @Test
    void blankRequiredFields_areRejected() {
        RegisterRequestDTO r = valid();
        r.setFirstName(null);
        r.setLastName(null);
        r.setEmail(null);
        assertThat(violatingFields(r)).contains("firstName", "lastName", "email");
    }

    // ── Trimming via JSON deserialization (rows 3, 4, 15) ─────────────────

    @Test
    void leadingTrailingSpaces_areTrimmed_thenAccepted() throws Exception {
        String json = """
                {"firstName":"  John  ","lastName":"Moyo","phoneNumber":"+263771234567",
                 "email":"john@example.com","country":"Zimbabwe","defaultServices":["loyalty"],
                 "isBusiness":false}
                """;
        RegisterRequestDTO r = mapper.readValue(json, RegisterRequestDTO.class);
        assertThat(r.getFirstName()).isEqualTo("John");     // trimmed
        assertThat(validator.validate(r)).isEmpty();
    }

    @Test
    void whitespaceOnlyName_becomesNull_andIsRequired() throws Exception {
        String json = """
                {"firstName":"   ","lastName":"Moyo","phoneNumber":"+263771234567",
                 "email":"john@example.com","country":"Zimbabwe","defaultServices":["loyalty"],
                 "isBusiness":false}
                """;
        RegisterRequestDTO r = mapper.readValue(json, RegisterRequestDTO.class);
        assertThat(r.getFirstName()).isNull();              // blank -> null
        assertThat(violatingFields(r)).contains("firstName");
    }
}
