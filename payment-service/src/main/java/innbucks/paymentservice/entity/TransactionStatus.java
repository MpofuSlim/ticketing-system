package innbucks.paymentservice.entity;

/**
 * Lifecycle of a money-movement attempt. Rows are inserted PENDING before
 * the Oradian call and flipped to SUCCEEDED or FAILED after — never the
 * other way around. A PENDING row older than the reconciliation threshold
 * means we lost the response (DB blip, JVM crash mid-flight, etc.) and
 * needs a manual check against Oradian.
 */
public enum TransactionStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
