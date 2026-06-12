package innbucks.paymentservice.client;

/**
 * Outcome of {@code POST /api/code/generate} (InnBucks Merchant API).
 *
 * @param approved        platform said responseCode 0 AND returned both
 *                        handles ({@code code} + {@code authNumber})
 * @param code            the InnBucks code the CUSTOMER pays (shown in their
 *                        app under "Pay by Code"; surfaced to the FE on the
 *                        payment response)
 * @param authNumber      InnBucks-side handle for this code — the
 *                        {@code originalReference} every status query keys on
 * @param qrCodeBase64    InnBucks-rendered QR image (base64) encoding the same
 *                        payment — surfaced to the FE so customers can
 *                        Scan-to-Pay instead of typing the code; null when
 *                        the platform omits it (the numeric code always works)
 * @param stan            InnBucks system trace audit number (logging only)
 * @param amountEchoCents the amount the platform echoed back, in CENTS —
 *                        callers MUST cross-check it against what they sent
 *                        (the cents-vs-dollars 100x guard); null when absent
 * @param responseCode    upstream response code as a string (for the journal)
 * @param responseMsg     upstream human-readable message
 */
public record CodeGenerationResult(
        boolean approved,
        String code,
        String authNumber,
        String qrCodeBase64,
        String stan,
        Long amountEchoCents,
        String responseCode,
        String responseMsg) {
}
