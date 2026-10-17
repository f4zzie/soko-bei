-- Soko Bei schema. Keyspace is created by the migration runner from config.
-- Every statement uses IF NOT EXISTS so a half-applied migration can be re-run safely.
-- PRINCIPLE: reports_by_market_commodity is the ONLY source of truth for prices.
-- Every other price table is a derived cache that can be rebuilt from it.

CREATE TABLE IF NOT EXISTS markets (
  market_id text PRIMARY KEY,
  name text, county text, lat double, lng double,
  created_at timestamp, updated_at timestamp
);

CREATE TABLE IF NOT EXISTS commodities (
  commodity_id text PRIMARY KEY,
  name_en text, name_sw text, default_unit text, grades list<text>,
  created_at timestamp, updated_at timestamp
);

CREATE TABLE IF NOT EXISTS commodity_units (
  commodity_id text, unit text, version int,
  kg_per_unit decimal, valid_from date, verified boolean, note text,
  PRIMARY KEY ((commodity_id), unit, version)
) WITH CLUSTERING ORDER BY (unit ASC, version DESC);

CREATE TABLE IF NOT EXISTS users_by_id (
  user_id uuid PRIMARY KEY,
  phone_e164 text, display_name text,
  consent_version int, consent_at timestamp,
  created_at timestamp
);

-- created with INSERT ... IF NOT EXISTS (the only LWT in the app)
CREATE TABLE IF NOT EXISTS users_by_phone (
  phone_e164 text PRIMARY KEY,
  user_id uuid
);

-- written with USING TTL 300
CREATE TABLE IF NOT EXISTS otp_codes (
  phone_e164 text PRIMARY KEY,
  code_hash text, attempts int, created_at timestamp
);

-- written with TTL matching expiry
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash text PRIMARY KEY,
  user_id uuid, expires_at timestamp
);

-- SOURCE OF TRUTH
CREATE TABLE IF NOT EXISTS reports_by_market_commodity (
  commodity_id text, market_id text,
  month text,                       -- 'YYYY-MM' in EAT from reported_at
  reported_at timeuuid,             -- deterministic from (report_id, reported_at_ms)
  report_id uuid, received_at timestamp, user_id uuid,
  grade text, unit text, quantity decimal, price_kes int,
  kg_per_unit decimal, conversion_version int, price_per_kg_kes decimal,
  PRIMARY KEY ((commodity_id, market_id, month), reported_at)
) WITH CLUSTERING ORDER BY (reported_at DESC);

CREATE TABLE IF NOT EXISTS reports_by_user (
  user_id uuid, year int, reported_at timeuuid,
  report_id uuid, commodity_id text, market_id text,
  grade text, unit text, quantity decimal, price_kes int,
  PRIMARY KEY ((user_id, year), reported_at)
) WITH CLUSTERING ORDER BY (reported_at DESC);

CREATE TABLE IF NOT EXISTS report_locator (
  report_id uuid PRIMARY KEY,
  commodity_id text, market_id text, month text,
  reported_at timeuuid, user_id uuid, payload_hash text
);

CREATE TABLE IF NOT EXISTS latest_price_by_commodity (
  commodity_id text, market_id text, grade text,
  market_name text, county text,
  median_today_per_kg decimal, last_price_per_kg decimal,
  reports_today int, updated_at timestamp,
  PRIMARY KEY ((commodity_id), market_id, grade)
);

CREATE TABLE IF NOT EXISTS latest_price_by_market (
  market_id text, commodity_id text, grade text,
  commodity_name_en text, commodity_name_sw text,
  median_today_per_kg decimal, last_price_per_kg decimal,
  reports_today int, updated_at timestamp,
  PRIMARY KEY ((market_id), commodity_id, grade)
);

CREATE TABLE IF NOT EXISTS daily_stats (
  commodity_id text, market_id text, year int,
  day date, grade text,
  min_per_kg decimal, median_per_kg decimal, max_per_kg decimal,
  report_count int, computed_at timestamp,
  PRIMARY KEY ((commodity_id, market_id, year), day, grade)
) WITH CLUSTERING ORDER BY (day DESC, grade ASC);

CREATE TABLE IF NOT EXISTS flags_by_report (
  report_id uuid, user_id uuid, reason text, created_at timestamp,
  PRIMARY KEY ((report_id), user_id)
);

CREATE TABLE IF NOT EXISTS job_state (
  job_name text PRIMARY KEY,
  last_success_at timestamp, last_processed_day date, last_error text
);

CREATE TABLE IF NOT EXISTS schema_migrations (
  version int PRIMARY KEY, name text, checksum text, applied_at timestamp
);
