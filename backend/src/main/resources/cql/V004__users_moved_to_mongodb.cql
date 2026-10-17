-- Users, OTP codes and refresh tokens now live in MongoDB (see DECISIONS.md).
-- Cassandra keeps all price data. These tables are superseded and dropped; no production data existed yet.
DROP TABLE IF EXISTS users_by_phone;
DROP TABLE IF EXISTS users_by_id;
DROP TABLE IF EXISTS otp_codes;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS refresh_tokens_by_user;
