CREATE SCHEMA IF NOT EXISTS app;

CREATE TABLE app.drafts (
    id          uuid PRIMARY KEY,
    title       text        NOT NULL,
    description text        NOT NULL DEFAULT '',
    author      text        NOT NULL,
    scope_geom  jsonb,
    status      text        NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'merged', 'partially_merged', 'closed')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX drafts_status_idx ON app.drafts (status, updated_at DESC);

CREATE TABLE app.changes (
    id            uuid PRIMARY KEY,
    draft_id      uuid        NOT NULL REFERENCES app.drafts (id) ON DELETE CASCADE,
    osm_type      text        NOT NULL CHECK (osm_type IN ('node', 'way', 'relation')),
    osm_id        bigint      NOT NULL,
    action        text        NOT NULL CHECK (action IN ('create', 'modify', 'delete')),
    base_version  integer,
    base_tags     jsonb       NOT NULL DEFAULT '{}',
    new_tags      jsonb       NOT NULL DEFAULT '{}',
    base_geom     jsonb,
    new_geom      jsonb,
    new_nodes     bigint[],
    new_members   jsonb,
    centroid_lon  double precision,
    centroid_lat  double precision,
    rule_id       text,
    reason        text,
    confidence    text        NOT NULL DEFAULT 'needs_review' CHECK (confidence IN ('deterministic', 'needs_review')),
    status        text        NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected', 'conflict', 'uploaded')),
    live_version  integer,
    live_tags     jsonb,
    changeset_id  bigint,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX changes_draft_idx ON app.changes (draft_id, created_at);
CREATE INDEX changes_object_idx ON app.changes (osm_type, osm_id);

CREATE TABLE app.reviews (
    id          bigserial PRIMARY KEY,
    change_id   uuid        NOT NULL REFERENCES app.changes (id) ON DELETE CASCADE,
    decision    text        NOT NULL CHECK (decision IN ('approve', 'edit', 'reject', 'send_to_id', 'reset')),
    comment     text,
    edited_tags jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reviews_change_idx ON app.reviews (change_id, created_at);

CREATE TABLE app.uploads (
    id           bigserial PRIMARY KEY,
    draft_id     uuid        NOT NULL REFERENCES app.drafts (id) ON DELETE CASCADE,
    changeset_id bigint,
    comment      text        NOT NULL,
    change_ids   uuid[]      NOT NULL,
    status       text        NOT NULL CHECK (status IN ('uploading', 'uploaded', 'failed', 'conflict')),
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.rule_runs (
    id          bigserial PRIMARY KEY,
    rule_id     text        NOT NULL,
    scope_key   text        NOT NULL,
    scope_geom  jsonb,
    issue_count integer     NOT NULL,
    took_ms     bigint      NOT NULL,
    ran_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.settings (
    key        text PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
