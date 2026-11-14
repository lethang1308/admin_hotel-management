ALTER TABLE saved_guests ADD COLUMN document_type varchar(30);
ALTER TABLE saved_guests ADD COLUMN document_number_encrypted text;
ALTER TABLE saved_guests ADD COLUMN document_number_hash char(64);
