CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email_normalized TEXT NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    avatar_ref TEXT,
    locale VARCHAR(8) NOT NULL DEFAULT 'ru',
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_app_user_email_normalized
        CHECK (email_normalized = LOWER(BTRIM(email_normalized))),
    CONSTRAINT ck_app_user_display_name
        CHECK (CHAR_LENGTH(BTRIM(display_name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_app_user_locale
        CHECK (locale IN ('ru', 'en')),
    CONSTRAINT ck_app_user_status
        CHECK (status IN ('active', 'pending_deletion', 'deleted')),
    CONSTRAINT ck_app_user_version
        CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_app_user_email_active
    ON app_user (email_normalized)
    WHERE deleted_at IS NULL;

CREATE TABLE password_credential (
    user_id UUID PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    password_hash TEXT NOT NULL,
    password_algorithm VARCHAR(32) NOT NULL DEFAULT 'argon2id',
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    CONSTRAINT ck_password_credential_hash
        CHECK (CHAR_LENGTH(password_hash) BETWEEN 20 AND 512),
    CONSTRAINT ck_password_credential_algorithm
        CHECK (password_algorithm IN ('argon2id')),
    CONSTRAINT ck_password_credential_failed_attempts
        CHECK (failed_attempts >= 0)
);

CREATE TABLE refresh_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by_session_id UUID,
    user_agent_hash BYTEA,
    CONSTRAINT ck_refresh_session_expiry
        CHECK (expires_at > created_at)
);

ALTER TABLE refresh_session
    ADD CONSTRAINT fk_refresh_session_replacement
        FOREIGN KEY (replaced_by_session_id)
        REFERENCES refresh_session (id)
        ON DELETE SET NULL;

CREATE INDEX ix_refresh_session_user_active
    ON refresh_session (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE household (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES app_user (id),
    default_expiry_horizon_days INTEGER NOT NULL DEFAULT 3,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_household_name
        CHECK (CHAR_LENGTH(BTRIM(name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_household_expiry_horizon
        CHECK (default_expiry_horizon_days BETWEEN 1 AND 30),
    CONSTRAINT ck_household_version
        CHECK (version > 0)
);

CREATE INDEX ix_household_owner_active
    ON household (owner_user_id)
    WHERE deleted_at IS NULL;

CREATE TABLE household_member (
    household_id UUID NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 1,
    deleted_at TIMESTAMPTZ,
    PRIMARY KEY (household_id, user_id),
    CONSTRAINT ck_household_member_role
        CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_household_member_status
        CHECK (status IN ('active', 'removed')),
    CONSTRAINT ck_household_member_version
        CHECK (version > 0)
);

CREATE INDEX ix_household_member_user_active
    ON household_member (user_id, household_id)
    WHERE deleted_at IS NULL AND status = 'active';

CREATE UNIQUE INDEX uq_household_single_active_owner
    ON household_member (household_id)
    WHERE deleted_at IS NULL AND status = 'active' AND role = 'OWNER';

CREATE TABLE household_invite (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE,
    created_by_user_id UUID NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER NOT NULL DEFAULT 1,
    used_count INTEGER NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_household_invite_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_household_invite_max_uses
        CHECK (max_uses BETWEEN 1 AND 100),
    CONSTRAINT ck_household_invite_used_count
        CHECK (used_count BETWEEN 0 AND max_uses)
);

CREATE INDEX ix_household_invite_active
    ON household_invite (household_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE storage_location (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 1,
    archived_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_storage_location_name
        CHECK (CHAR_LENGTH(BTRIM(name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_storage_location_kind
        CHECK (kind IN ('fridge', 'freezer', 'pantry', 'custom')),
    CONSTRAINT ck_storage_location_version
        CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_storage_location_name_active
    ON storage_location (household_id, LOWER(name))
    WHERE deleted_at IS NULL AND archived_at IS NULL;

CREATE INDEX ix_storage_location_household_active
    ON storage_location (household_id, sort_order)
    WHERE deleted_at IS NULL;

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES app_user (id) ON DELETE CASCADE,
    operation_scope VARCHAR(120) NOT NULL,
    key_hash BYTEA NOT NULL,
    request_hash BYTEA NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_record_scope_key
        UNIQUE (actor_user_id, operation_scope, key_hash),
    CONSTRAINT ck_idempotency_record_status
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT ck_idempotency_record_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX ix_idempotency_record_expiry
    ON idempotency_record (expires_at);

COMMENT ON COLUMN password_credential.password_hash IS
    'Argon2id encoded hash only; never plaintext or reversible encryption';
COMMENT ON COLUMN refresh_session.token_hash IS
    'Cryptographic hash of refresh token; raw token is never persisted';
COMMENT ON COLUMN household_invite.token_hash IS
    'Cryptographic hash of invite code; raw code is never persisted';
