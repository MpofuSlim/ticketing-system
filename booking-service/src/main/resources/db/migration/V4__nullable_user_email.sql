-- Allow bookings without an email — customers may register with phone only.
ALTER TABLE bookings ALTER COLUMN user_email DROP NOT NULL;
