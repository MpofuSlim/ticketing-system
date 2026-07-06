-- A02: password hashing moved from BCrypt to Argon2id (DelegatingPasswordEncoder
-- in SecurityConfig). MFA backup-code hashes are produced by the SAME
-- PasswordEncoder (MfaService.completeEnrolment), and an Argon2id encoded hash
-- (~100 chars including the "{argon2}" prefix) overflows the original
-- VARCHAR(72) column, which was sized for BCrypt's 60-char output. The overflow
-- surfaced as a DataIntegrityViolationException on MFA enrolment. Widen the
-- column so both legacy BCrypt (60) and new Argon2id (~100) hashes fit.
-- Existing rows are unaffected (a widening ALTER preserves data), and the
-- (user_id, code_hash) uniqueness constraint is unchanged.
ALTER TABLE mfa_backup_codes ALTER COLUMN code_hash TYPE VARCHAR(255);
