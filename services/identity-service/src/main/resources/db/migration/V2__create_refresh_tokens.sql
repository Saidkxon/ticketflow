-- Refresh tokens (ADR-0004). Only a hash of each token is stored, never the token itself.
-- Every login starts a "family". Each use of a token creates the next token in the same family
-- (rotation). Using a token a second time revokes the whole family.
CREATE TABLE refresh_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash text        NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz,
    version    bigint      NOT NULL DEFAULT 0
);

-- A token is found by its hash; the hash is unique.
CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON refresh_tokens (token_hash);
-- Revoking a family touches every token of that family.
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
-- Cleaning up expired tokens, and deleting a user's tokens.
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
