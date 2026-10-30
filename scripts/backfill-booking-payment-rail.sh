#!/usr/bin/env bash
# One-off: fill bookings.payment_rail (booking-service V29) for bookings
# confirmed before payment-service started sending the rail with the confirm.
# The rail lives in payment-service's own database (payment.payment_rail), and
# the two databases cannot join, so this reads the paid booking payments there
# and writes them into booking_service.
#
# Run it on the box AFTER the V29 booking-service image is rolled out (the
# column must exist). Safe to run again: it only fills rows still NULL, only
# on CONFIRMED bookings, and never changes a rail already recorded.
#
#   ~/ticketing-system/scripts/backfill-booking-payment-rail.sh          # dry run: counts only
#   APPLY=1 ~/ticketing-system/scripts/backfill-booking-payment-rail.sh  # write
#
# "Paid" = SUCCEEDED or COMPLETED_UNCONFIRMED (money moved). One payment per
# booking can be paid (V12's one-active-payment index); the latest is taken
# if history ever holds more.
set -euo pipefail

NS="${NS:-ticketing}"
PGPOD="${PGPOD:-postgres-0}"
PGUSER="${PGUSER:-postgres}"
psql_in() { kubectl -n "$NS" exec -i "$PGPOD" -- psql -U "$PGUSER" -v ON_ERROR_STOP=1 -At "$@"; }

STATEMENTS="$(psql_in -d payment_service -c "
  SELECT DISTINCT ON (order_ref)
         format('UPDATE bookings SET payment_rail = %L WHERE id = %L::uuid '
                'AND payment_rail IS NULL AND status = %L;', payment_rail, order_ref, 'CONFIRMED')
    FROM payment
   WHERE order_type = 'BOOKING'
     AND status IN ('SUCCEEDED', 'COMPLETED_UNCONFIRMED')
     AND order_ref ~ '^[0-9a-fA-F-]{36}$'
   ORDER BY order_ref, created_at DESC;")"

FOUND="$(printf '%s\n' "$STATEMENTS" | grep -c '^UPDATE' || true)"
echo "Paid booking payments found in payment_service: $FOUND"
echo "Confirmed bookings still without a payment method:"
psql_in -d booking_service -c "SELECT count(*) FROM bookings WHERE status = 'CONFIRMED' AND payment_rail IS NULL;"

if [ "${APPLY:-0}" != "1" ]; then
  echo "Dry run. Re-run with APPLY=1 to write."
  exit 0
fi

{ echo "BEGIN;"; printf '%s\n' "$STATEMENTS"; echo "COMMIT;"; } | psql_in -d booking_service -q
echo "Done. Confirmed bookings still without a payment method (no paid payment on record, e.g. made by hand):"
psql_in -d booking_service -c "SELECT count(*) FROM bookings WHERE status = 'CONFIRMED' AND payment_rail IS NULL;"
echo "By method:"
psql_in -d booking_service -c "SELECT coalesce(payment_rail, '(none)'), count(*) FROM bookings WHERE status = 'CONFIRMED' GROUP BY 1 ORDER BY 2 DESC;"
