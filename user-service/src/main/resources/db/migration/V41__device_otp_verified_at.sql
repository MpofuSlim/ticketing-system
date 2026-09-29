-- DTX device security: separate "this phone passed a PIN login" from "this phone
-- proved it holds the SIM".
--
-- In watch mode (DEVICE_SECURITY_ENFORCE_OTP=false) every phone is answered
-- TOKEN, and a successful PIN login bound it TRUSTED for the full trust window
-- (90 days). That trust was indistinguishable from trust earned through an OTP,
-- so flipping ENFORCE_OTP on later would have left every phone that signed in
-- during watch mode exempt from the code it was never asked for — including a
-- phone an attacker signed in on with a stolen PIN.
--
-- NULL = this phone has never verified a sign-in OTP for this number. A TRUSTED
-- row with NULL here is PROVISIONAL: once OTP is enforced it is asked for one
-- code before it is trusted again (DeviceSignInService).
ALTER TABLE customer_devices ADD COLUMN otp_verified_at TIMESTAMP;

-- Credit the rows that DID pass a code (a cell that already ran with OTP
-- enforced, or a staging test number), so nobody is asked twice. A no-op on a
-- cell that has only ever watched. Challenges older than their retention window
-- are gone, so such a row is asked once more — the safe direction.
UPDATE customer_devices d
SET otp_verified_at = c.verified_at
FROM (SELECT customer_device_id, MAX(closed_at) AS verified_at
      FROM device_otp_challenges
      WHERE status = 'VERIFIED' AND purpose IN ('SIGN_IN', 'PIN_ISSUE')
      GROUP BY customer_device_id) c
WHERE c.customer_device_id = d.id;
