-- Keep superseded price snapshots when a date is released and later booked again on the same line.
ALTER TABLE booking_room_nights ADD COLUMN revision INT NOT NULL DEFAULT 1;
CREATE UNIQUE INDEX uq_booking_room_night_revision ON booking_room_nights (booking_room_id, stay_date, revision);
ALTER TABLE booking_room_nights DROP INDEX booking_room_nights_index_50;

-- Give a retried service submission the same usage and ledger postings.
ALTER TABLE service_usages ADD COLUMN request_key VARCHAR(24);
CREATE UNIQUE INDEX uq_service_usage_request ON service_usages (booking_id, request_key);
