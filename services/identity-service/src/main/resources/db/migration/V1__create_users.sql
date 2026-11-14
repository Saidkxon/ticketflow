-- Users and their roles (ADR-0004). The password is stored only as a hash.
CREATE TABLE users (
    id            uuid        PRIMARY KEY,
    email         text        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 254),
    password_hash text        NOT NULL,
    display_name  text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 80),
    created_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0
);

-- One account per email, ignoring case ("A@x.com" and "a@x.com" are the same person).
CREATE UNIQUE INDEX users_email_lower_key ON users (lower(email));

-- A user can hold several roles. The foreign key stays inside this service's own database (ADR-0005).
CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    text NOT NULL CHECK (role IN ('CUSTOMER', 'ORGANIZER', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);
