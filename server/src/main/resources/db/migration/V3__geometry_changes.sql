ALTER TABLE app.changes
    ADD COLUMN base_shape     jsonb,
    ADD COLUMN new_lat        double precision,
    ADD COLUMN new_lon        double precision,
    ADD COLUMN live_shape     jsonb,
    ADD COLUMN live_geom      jsonb,
    ADD COLUMN if_unused      boolean NOT NULL DEFAULT false,
    ADD COLUMN placeholder_id bigint;

CREATE INDEX changes_draft_object_idx ON app.changes (draft_id, osm_type, osm_id);
