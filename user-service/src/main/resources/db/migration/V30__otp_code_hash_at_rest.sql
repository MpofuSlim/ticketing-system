-- OWASP A02 — stop storing OTP codes in plaintext.
--
-- otps.code held the raw 6-digit code. A DB read during an OTP's ~5-minute live
-- window handed an attacker usable codes for password-reset / PIN-setup flows.
-- OtpHasher now stores an HMAC-SHA256 (keyed by otp.hmac-secret) of the code;
-- verification hashes the submitted code and matches HMAC-to-HMAC via the
-- existing OtpRepository.consume query. Widen the column from VARCHAR(6) to
-- hold the 64-char lowercase-hex digest.
--
-- No backfill: OTPs are ephemeral (5-min TTL). Any plaintext rows written just
-- before deploy simply fail to verify (the submitted code hashes to 64 chars
-- and won't match a 6-char row) and expire within the window; affected users
-- re-request. purgeExpired() clears them on its next sweep.

ALTER TABLE otps ALTER COLUMN code TYPE VARCHAR(64);
