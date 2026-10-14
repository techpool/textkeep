CREATE TABLE IF NOT EXISTS textkeep.revoked_web_sessions(
  hash text PRIMARY KEY,
  expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS revoked_web_sessions_expiry ON textkeep.revoked_web_sessions(expires_at);
