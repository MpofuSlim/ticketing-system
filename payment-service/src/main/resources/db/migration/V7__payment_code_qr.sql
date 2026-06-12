-- Scan-to-Pay: persist the InnBucks-rendered QR image (base64) returned by
-- /api/code/generate alongside the numeric code. The FE renders it so the
-- customer can scan with the InnBucks app instead of typing the code; kept
-- on the row so replays re-surface the exact artifact the customer saw.
ALTER TABLE payment ADD COLUMN code_qr_base64 TEXT;
