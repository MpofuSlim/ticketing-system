package zw.co.innbucks.coregateway;

import java.util.Map;

/**
 * Inbound request body for POST /notifications/whatsapp.
 *
 * <p>WhatsApp Business has two delivery modes and the caller picks one:
 * <ul>
 *   <li><b>Template-initiated</b> (most common): supply {@code templateId} +
 *       {@code templateVariables}. The platform resolves the provider-agnostic
 *       template id to the upstream Cloud API template and substitutes the
 *       variables. Valid outside the 24h customer-service window.</li>
 *   <li><b>Free-text</b>: supply {@code bodyText}. Only deliverable inside the
 *       24h session window opened by an inbound customer message — messenger
 *       will reject otherwise. Mirrors the external wasenda
 *       {@code custom-notification} shape the ticketing services use today.</li>
 * </ul>
 *
 * @param destination       Phone number in E.164 (e.g. +263771234567).
 * @param templateId        Approved template id (provider-agnostic). Mutually
 *                          exclusive with {@code bodyText}.
 * @param templateVariables Placeholder substitutions for the template body.
 * @param bodyText          Free-text body (24h session-window only). Mutually
 *                          exclusive with {@code templateId}.
 * @param mediaUrl          Optional media (image/document) URL.
 * @param reference         Caller-assigned unique reference for
 *                          idempotency / status lookup. UUID generated if
 *                          omitted.
 * @param participantId     InnBucks participant account charged for the
 *                          message. Optional; required by some
 *                          messenger-interface configurations.
 */
record WhatsAppRequest(
        String destination,
        String templateId,
        Map<String, String> templateVariables,
        String bodyText,
        String mediaUrl,
        String reference,
        String participantId
) {}
