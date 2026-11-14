ALTER TABLE bookings ADD COLUMN online_request_key varchar(64);
CREATE UNIQUE INDEX uq_booking_online_request ON bookings (customer_id, online_request_key);

CREATE TABLE saved_guests (
    id bigint PRIMARY KEY AUTO_INCREMENT,
    customer_id bigint NOT NULL,
    full_name varchar(150) NOT NULL,
    date_of_birth date,
    gender varchar(20),
    guest_type varchar(20) NOT NULL DEFAULT 'ADULT',
    phone varchar(20),
    CONSTRAINT fk_saved_guest_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
    CONSTRAINT ck_saved_guest_type CHECK (guest_type IN ('ADULT','CHILD')),
    CONSTRAINT ck_saved_guest_gender CHECK (gender IS NULL OR gender IN ('MALE','FEMALE','OTHER'))
);
CREATE INDEX ix_saved_guest_owner ON saved_guests (customer_id, id);
