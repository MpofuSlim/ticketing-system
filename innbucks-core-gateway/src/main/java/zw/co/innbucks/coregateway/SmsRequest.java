package zw.co.innbucks.coregateway;

/**
 * Inbound request body for POST /notifications/sms.
 *
 * @param destination  Phone number in E.164 format (e.g. +263771234567).
 * @param message      SMS text content (max 1600 chars).
 * @param reference    Caller-assigned unique reference for idempotency / tracking.
 *                     A UUID is generated if omitted.
 * @param senderId     Alphanumeric sender ID shown on the handset (e.g. "INNBUCKS").
 *                     Defaults to "INNBUCKS" if omitted.
 * @param participantId InnBucks participant account charged for the SMS. Optional;
 *                      required by some messenger-interface configurations — obtain
 *                      the correct value from the InnBucks platform team.
 */
record SmsRequest(
        String destination,
        String message,
        String reference,
        String senderId,
        String participantId
) {}
