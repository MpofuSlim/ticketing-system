package innbucks.paymentservice.client;

/**
 * What Paynow returned when a payment was started ({@code status=Ok}, hash
 * verified). Which fields are set depends on the flow:
 *
 * @param browserUrl      web checkout: Paynow's page for the customer
 * @param pollUrl         every flow: where we POST to read the status
 * @param paynowReference Paynow's reference, when it sent one
 * @param otpReference    O'mari: the reference of the code texted to the customer
 * @param remoteOtpUrl    O'mari: where we POST the code the customer types
 * @param instructions    express checkout: Paynow's instructions text, if any
 */
public record PaynowInitiation(
        String browserUrl,
        String pollUrl,
        String paynowReference,
        String otpReference,
        String remoteOtpUrl,
        String instructions) {
}
