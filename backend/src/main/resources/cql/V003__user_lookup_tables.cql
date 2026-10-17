-- Account deletion needs to find a user's refresh tokens and flags without scanning or secondary indexes
-- (one table per query). Both are written alongside refresh_tokens / flags_by_report.
CREATE TABLE IF NOT EXISTS refresh_tokens_by_user (
  user_id uuid, token_hash text, expires_at timestamp,
  PRIMARY KEY ((user_id), token_hash)
);

CREATE TABLE IF NOT EXISTS flags_by_user (
  user_id uuid, report_id uuid, created_at timestamp,
  PRIMARY KEY ((user_id), report_id)
);
