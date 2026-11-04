package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

/**
 * A refusal while adding a business to an InnBucks app account that already
 * holds the phone ({@code BusinessRegistrationService}). Rendered like every
 * {@link OrganizationException}: {@code data.errorCode} is what the Foundry
 * portal's registration page branches on.
 */
public class BusinessRegistrationException extends OrganizationException {

    /** 409: the number has an app account; send a code to it and register again with {@code phoneCode}. */
    public static final String PHONE_CODE_REQUIRED = "phone_code_required";
    public static final String PHONE_CODE_REQUIRED_MESSAGE = "This number already has an InnBucks app account. "
            + "We can add your business to it: ask for a code, then send your registration again with the code.";

    /** 400: the code is wrong, used up, or expired. */
    public static final String PHONE_CODE_INVALID = "phone_code_invalid";
    public static final String PHONE_CODE_INVALID_MESSAGE = "That code is wrong or has expired. Ask for a new one.";

    /** 409: a code was asked for a number with no account; registering needs none. */
    public static final String PHONE_CODE_NOT_NEEDED = "phone_code_not_needed";
    public static final String PHONE_CODE_NOT_NEEDED_MESSAGE =
            "This number has no InnBucks account yet. Register without a code.";

    /** 409: the business registration on this account is already waiting for approval. */
    public static final String PENDING = "business_registration_pending";
    public static final String PENDING_MESSAGE = "Your business registration is already waiting for approval.";

    public BusinessRegistrationException(HttpStatus status, String errorCode, String message) {
        super(status, errorCode, message);
    }

    public static BusinessRegistrationException phoneCodeRequired() {
        return new BusinessRegistrationException(HttpStatus.CONFLICT, PHONE_CODE_REQUIRED, PHONE_CODE_REQUIRED_MESSAGE);
    }

    public static BusinessRegistrationException phoneCodeInvalid() {
        return new BusinessRegistrationException(HttpStatus.BAD_REQUEST, PHONE_CODE_INVALID, PHONE_CODE_INVALID_MESSAGE);
    }

    public static BusinessRegistrationException phoneCodeNotNeeded() {
        return new BusinessRegistrationException(HttpStatus.CONFLICT, PHONE_CODE_NOT_NEEDED,
                PHONE_CODE_NOT_NEEDED_MESSAGE);
    }

    public static BusinessRegistrationException pending() {
        return new BusinessRegistrationException(HttpStatus.CONFLICT, PENDING, PENDING_MESSAGE);
    }
}
