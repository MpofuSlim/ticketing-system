-- A01/A04: records the LAST time this phone completed OTP verification, so the
-- tier2/3/4 KYC-upgrade endpoints can require a RECENT verification (proof the
-- caller owns the phone) rather than trusting the request-body msisdn.
ALTER TABLE customer_profiles ADD COLUMN phone_verified_at TIMESTAMP;
