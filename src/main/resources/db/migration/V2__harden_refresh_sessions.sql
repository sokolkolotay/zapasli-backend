ALTER TABLE refresh_session
    ADD CONSTRAINT ck_refresh_session_token_hash_length
        CHECK (OCTET_LENGTH(token_hash) = 32),
    ADD CONSTRAINT ck_refresh_session_user_agent_hash_length
        CHECK (user_agent_hash IS NULL OR OCTET_LENGTH(user_agent_hash) = 32);

CREATE INDEX ix_refresh_session_family
    ON refresh_session (family_id);

COMMENT ON COLUMN refresh_session.family_id IS
    'Stable token-family identifier used to revoke every descendant after reuse';
