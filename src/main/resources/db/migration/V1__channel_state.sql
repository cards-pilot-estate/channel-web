-- The web channel's own state (ADR-0020, docs/channels/web-channel.md §3.3.11). The platform never
-- reads this database. Personal data in a draft is encrypted (AES-GCM) by the BFF before it is stored.

-- One draft per customer: answers so far, the consents given before anything was collected (R3),
-- and the submission's idempotency key once the customer presses Submit.
CREATE TABLE draft (
    id                  UUID         PRIMARY KEY,
    customer_key        VARCHAR(64)  NOT NULL UNIQUE,
    answers             BYTEA        NOT NULL,
    sections_done       VARCHAR(200) NOT NULL DEFAULT '',
    notice_version      VARCHAR(40)  NOT NULL,
    locale              VARCHAR(10)  NOT NULL,
    consent_processing  BOOLEAN      NOT NULL,
    consent_bureau      BOOLEAN      NOT NULL,
    consent_marketing   BOOLEAN      NOT NULL,
    submission_key      VARCHAR(64),
    row_version         INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_draft_expires ON draft (expires_at);

-- Which customer submitted which application: the BFF serves an application only to its owner.
CREATE TABLE customer_application (
    application_id   UUID         PRIMARY KEY,
    customer_key     VARCHAR(64)  NOT NULL,
    application_ref  VARCHAR(30)  NOT NULL,
    product          VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_customer_application_customer ON customer_application (customer_key, created_at DESC);

-- Consent evidence: which notice version, which purposes, when (DPDP Rule 3; W10.02 moves the
-- full ledger to the platform).
CREATE TABLE consent_record (
    id                      UUID         PRIMARY KEY,
    customer_key            VARCHAR(64)  NOT NULL,
    notice_version          VARCHAR(40)  NOT NULL,
    locale                  VARCHAR(10)  NOT NULL,
    processing              BOOLEAN      NOT NULL,
    bureau_check            BOOLEAN      NOT NULL,
    marketing               BOOLEAN      NOT NULL,
    given_at                TIMESTAMPTZ  NOT NULL,
    application_id          UUID
);
CREATE INDEX idx_consent_customer ON consent_record (customer_key, given_at DESC);
