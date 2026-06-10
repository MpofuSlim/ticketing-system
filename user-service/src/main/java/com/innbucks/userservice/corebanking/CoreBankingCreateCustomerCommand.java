package com.innbucks.userservice.corebanking;

import lombok.Builder;

import java.time.LocalDate;
import java.util.Map;

/**
 * Provider-neutral "create customer in core banking" command. Field set is
 * exactly what tier-2 registration collects; each adapter translates it to
 * its provider's wire shape (and enforces provider-specific constraints,
 * e.g. supported gender values) before the call.
 *
 * @param gender enum NAME from {@link com.innbucks.userservice.entity.CustomerProfile.Gender}
 *               ({@code MALE} / {@code FEMALE} / {@code OTHER}) — adapters
 *               reject values their provider can't represent.
 */
@Builder
public record CoreBankingCreateCustomerCommand(
        String firstName,
        String middleName,
        String lastName,
        LocalDate dateOfBirth,
        String gender,
        String msisdn,
        String nationalId,
        String email,
        Address address,
        Map<String, String> clientCustomFields
) {
    @Builder
    public record Address(String street1, String city, String postCode, String country) {}
}
