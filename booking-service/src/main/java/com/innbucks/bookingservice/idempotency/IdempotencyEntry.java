package com.innbucks.bookingservice.idempotency;

/**
 * State of an idempotency-key slot in the store.
 *
 * {@link Reserved} is set by {@link IdempotencyStore#tryReserve} when a
 * request starts processing; it blocks concurrent callers using the same
 * key from doing the same work and surfaces a 409 instead.
 * {@link Completed} replaces the reservation once the request finishes
 * with a 2xx and carries the snapshot to replay on subsequent calls.
 */
public sealed interface IdempotencyEntry permits IdempotencyEntry.Reserved, IdempotencyEntry.Completed {

    record Reserved() implements IdempotencyEntry {}

    record Completed(StoredResponse response) implements IdempotencyEntry {}
}
