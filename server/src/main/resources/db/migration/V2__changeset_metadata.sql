ALTER TABLE app.drafts
    ADD COLUMN area              text,
    ADD COLUMN changeset_comment text,
    ADD COLUMN changeset_source  text;

ALTER TABLE app.reviews
    ADD COLUMN imagery text;
