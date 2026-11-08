ALTER TABLE housekeeping_tasks ADD COLUMN task_kind varchar(20) NOT NULL DEFAULT 'TURNOVER';
ALTER TABLE housekeeping_tasks ADD COLUMN priority varchar(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE housekeeping_tasks ADD CONSTRAINT ck_housekeeping_kind CHECK (task_kind IN ('TURNOVER','STAY_OVER') AND priority IN ('NORMAL','HIGH'));
ALTER TABLE maintenance_requests ADD COLUMN request_key varchar(24) UNIQUE;
CREATE TABLE booking_room_preferences (
 booking_room_id bigint PRIMARY KEY,
 booking_id bigint NOT NULL,
 tags varchar(500),
 extra_beds int NOT NULL DEFAULT 0,
 extra_guests int NOT NULL DEFAULT 0,
 revision int NOT NULL DEFAULT 0,
 CONSTRAINT fk_preferences_line FOREIGN KEY (booking_room_id,booking_id) REFERENCES booking_rooms(id,booking_id),
 CONSTRAINT ck_preferences_values CHECK (extra_beds BETWEEN 0 AND 10 AND extra_guests BETWEEN 0 AND 10 AND revision >= 0)
);
CREATE TABLE guest_messages (
 id bigint PRIMARY KEY AUTO_INCREMENT,
 booking_id bigint NOT NULL,
 request_key varchar(150) NOT NULL UNIQUE,
 message_type varchar(40) NOT NULL,
 recipient varchar(200) NOT NULL,
 subject varchar(200) NOT NULL,
 body text NOT NULL,
 qr_payload varchar(100),
 created_by bigint,
 created_at datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
 CONSTRAINT fk_guest_message_booking FOREIGN KEY (booking_id) REFERENCES bookings(id),
 CONSTRAINT fk_guest_message_actor FOREIGN KEY (created_by) REFERENCES users(id),
 CONSTRAINT ck_guest_message_type CHECK (message_type IN ('CONFIRMATION','CHANGE','CANCELLATION','NO_SHOW'))
);
CREATE INDEX guest_messages_booking ON guest_messages(booking_id,created_at);
