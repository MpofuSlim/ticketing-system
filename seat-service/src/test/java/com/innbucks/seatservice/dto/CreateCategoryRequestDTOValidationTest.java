package com.innbucks.seatservice.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the bean-validation rules that gate the seat-creation DoS surface.
 * Bean validation runs at the controller @Valid boundary, so these tests
 * exercise the annotations directly with the JSR-380 reference validator.
 */
class CreateCategoryRequestDTOValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private SectionSeatConfigDTO section(String label, int count) {
        SectionSeatConfigDTO s = new SectionSeatConfigDTO();
        s.setSection(label);
        s.setSeatCount(count);
        return s;
    }

    private CreateCategoryRequestDTO request(List<SectionSeatConfigDTO> sections) {
        CreateCategoryRequestDTO req = new CreateCategoryRequestDTO();
        req.setEventId(UUID.randomUUID());
        req.setName("VIP");
        req.setPrice(new BigDecimal("20.00"));
        req.setSections(sections);
        return req;
    }

    @Test
    void seatCount_aboveMax_isRejected() {
        var violations = validator.validate(request(List.of(section("A", 100_001))));

        assertTrue(hasViolationOnPath(violations, "seatCount"),
                "seatCount > 100,000 must be rejected");
    }

    @Test
    void seatCount_atMax_isAccepted() {
        var violations = validator.validate(request(List.of(section("A", 100_000))));

        assertFalse(hasViolationOnPath(violations, "seatCount"));
    }

    @Test
    void seatCount_atIntegerMaxValue_isRejected() {
        // Without @Max this would slip through bean validation and only be
        // caught (or not) downstream — confirms the upper bound is enforced.
        var violations = validator.validate(request(List.of(section("A", Integer.MAX_VALUE))));

        assertTrue(hasViolationOnPath(violations, "seatCount"));
    }

    @Test
    void sections_aboveMaxListSize_isRejected() {
        List<SectionSeatConfigDTO> tooMany = Stream.iterate(0, i -> i + 1)
                .limit(101)
                .map(i -> section("S" + i, 1))
                .toList();

        var violations = validator.validate(request(tooMany));

        assertTrue(hasViolationOnPath(violations, "sections"),
                "> 100 sections must be rejected");
    }

    @Test
    void sections_atMaxListSize_isAccepted() {
        List<SectionSeatConfigDTO> exactly100 = Stream.iterate(0, i -> i + 1)
                .limit(100)
                .map(i -> section("S" + i, 1))
                .toList();

        var violations = validator.validate(request(exactly100));

        assertFalse(hasViolationOnPath(violations, "sections"));
    }

    @Test
    void sectionLabel_overlyLong_isRejected() {
        String overlyLong = "A".repeat(51);

        var violations = validator.validate(request(List.of(section(overlyLong, 1))));

        assertTrue(hasViolationOnPath(violations, "section"));
    }

    @Test
    void validRequest_hasNoViolations() {
        var violations = validator.validate(request(List.of(section("A", 25), section("B", 25))));

        assertTrue(violations.isEmpty(), "valid request should produce no violations, got: " + violations);
    }

    private boolean hasViolationOnPath(Set<? extends ConstraintViolation<?>> violations, String fieldSuffix) {
        return violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().endsWith(fieldSuffix));
    }
}
