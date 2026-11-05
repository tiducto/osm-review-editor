CREATE TABLE app.scans (
    id            uuid PRIMARY KEY,
    scope_key     text        NOT NULL,
    area_name     text,
    scope_geom    jsonb,
    request       jsonb       NOT NULL,
    rule_set      text[]      NOT NULL,
    rule_set_key  text        NOT NULL,
    rule_hashes   jsonb       NOT NULL DEFAULT '{}',
    status        text        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'done', 'failed')),
    trigger       text        NOT NULL DEFAULT 'manual' CHECK (trigger IN ('manual', 'mirror_swap')),
    mirror_file   text,
    issue_count   integer     NOT NULL DEFAULT 0,
    error_count   integer     NOT NULL DEFAULT 0,
    warning_count integer     NOT NULL DEFAULT 0,
    info_count    integer     NOT NULL DEFAULT 0,
    took_ms       bigint,
    error         text,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz
);
CREATE INDEX scans_scope_idx ON app.scans (scope_key, rule_set_key, started_at DESC);
CREATE INDEX scans_started_idx ON app.scans (started_at DESC);

CREATE TABLE app.scan_issues (
    id            bigserial PRIMARY KEY,
    scan_id       uuid             NOT NULL REFERENCES app.scans (id) ON DELETE CASCADE,
    rule          text             NOT NULL,
    file          text,
    line          integer,
    severity      text             NOT NULL CHECK (severity IN ('info', 'warning', 'error')),
    issue_group   text,
    message       text             NOT NULL,
    osm_type      text             NOT NULL CHECK (osm_type IN ('node', 'way', 'relation')),
    osm_id        bigint           NOT NULL,
    centroid_lon  double precision NOT NULL,
    centroid_lat  double precision NOT NULL,
    fix           jsonb,
    delete_object boolean          NOT NULL DEFAULT false,
    fingerprint   text             NOT NULL
);
CREATE INDEX scan_issues_scan_idx ON app.scan_issues (scan_id, severity, issue_group);
CREATE INDEX scan_issues_fingerprint_idx ON app.scan_issues (scan_id, fingerprint);
