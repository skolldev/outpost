-- ADR-0017: an API Token is either Personal (owned by an Outpost User, and
-- revoked the moment that account is deleted) or Installation (owned by nobody,
-- so it outlives whoever created it). Nullable rather than mandatory because a
-- CI token belongs to the Installation, not to whichever Admin ran the setup —
-- cascading it would turn offboarding that Admin into a broken build. Existing
-- rows migrate to NULL and keep working unchanged.
ALTER TABLE api_token
    ADD COLUMN owner_user_id bigint REFERENCES app_user (id) ON DELETE CASCADE;

-- The Member token list filters on this column, and the cascade needs it to
-- find the rows to remove when an account is deleted.
CREATE INDEX idx_api_token_owner ON api_token (owner_user_id);
