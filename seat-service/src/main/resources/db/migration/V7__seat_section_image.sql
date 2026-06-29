-- Optional per-section image (a URL the FE renders as the section's preview,
-- e.g. a seat-map thumbnail). Sections are not first-class rows in this service
-- — they're derived by grouping seats on row_label — so the image lives on the
-- seat rows and is recovered by taking it from any seat in the section on read.
-- Nullable: most sections won't have one. VARCHAR(1024) comfortably fits a CDN
-- URL while capping abuse. ddl-auto=validate, so this matches Seat.sectionImageUrl.
ALTER TABLE seats
    ADD COLUMN section_image_url VARCHAR(1024);
