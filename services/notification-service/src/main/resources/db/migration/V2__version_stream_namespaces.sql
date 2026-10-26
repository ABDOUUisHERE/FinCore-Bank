-- Versions belong to one producer aggregate stream, even when opaque UUIDs coincide.
ALTER TABLE aggregate_watermark ADD COLUMN aggregate_kind varchar(40);
UPDATE aggregate_watermark SET aggregate_kind=split_part(event_type,'.',1);
ALTER TABLE aggregate_watermark ALTER COLUMN aggregate_kind SET NOT NULL;
ALTER TABLE aggregate_watermark DROP CONSTRAINT aggregate_watermark_pkey;
ALTER TABLE aggregate_watermark ADD PRIMARY KEY(aggregate_kind,aggregate_id);
