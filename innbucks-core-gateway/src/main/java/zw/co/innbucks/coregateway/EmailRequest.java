package zw.co.innbucks.coregateway;

/**
 * Inbound request body for POST /notifications/email.
 *
 * <p>The body is pre-rendered by the caller — messenger-interface runs no
 * template engine on this path; it just forwards the rendered subject/body
 * to its SMTP provider. {@code isHtml} flips the content type the provider
 * sends.
 *
 * @param to          Primary recipients (at least one required).
 * @param cc          Carbon-copy recipients (optional).
 * @param bcc         Blind-carbon-copy recipients (optional).
 * @param fromAddress Optional From address override; defaults to the
 *                    gateway's configured sender.
 * @param fromName    Optional friendly From name shown in the recipient's
 *                    inbox; only used when {@code fromAddress} is set.
 * @param subject     Email subject line (required).
 * @param body        Pre-rendered email body (required). HTML or plain text
 *                    depending on {@code isHtml}.
 * @param isHtml      True if {@code body} is HTML; false for plain text.
 * @param reference   Caller-assigned unique reference for idempotency /
 *                    status lookup. UUID generated if omitted.
 * @param participantId InnBucks participant account charged for the email.
 *                      Optional.
 */
record EmailRequest(
        String[] to,
        String[] cc,
        String[] bcc,
        String fromAddress,
        String fromName,
        String subject,
        String body,
        boolean isHtml,
        String reference,
        String participantId
) {}
