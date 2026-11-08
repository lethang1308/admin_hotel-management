-- Operational additions; preserve all existing bookings, charges and payments.
ALTER TABLE hotel_settings ADD COLUMN bank_bin VARCHAR(6);
ALTER TABLE hotel_settings ADD COLUMN bank_account_number VARCHAR(19);
ALTER TABLE hotel_settings ADD COLUMN bank_account_name VARCHAR(50);
ALTER TABLE hotel_settings ADD COLUMN bank_revision INT NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN buyer_company_name VARCHAR(200);
ALTER TABLE payment_transactions ADD COLUMN receipt_storage_key VARCHAR(80);
ALTER TABLE payment_transactions ADD COLUMN receipt_sha256 CHAR(64);

CREATE TABLE refund_requests (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id BIGINT NOT NULL,
  parent_payment_id BIGINT NOT NULL,
  request_key VARCHAR(24) NOT NULL UNIQUE,
  amount DECIMAL(15,2) NOT NULL,
  method VARCHAR(30) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  requested_by BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  handled_by BIGINT,
  handled_at DATETIME(6),
  response_note VARCHAR(500),
  payment_transaction_id BIGINT UNIQUE,
  created_at DATETIME(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT ck_refund_request_values CHECK(amount>0 AND method IN ('CASH','BANK_TRANSFER') AND status IN ('PENDING','COMPLETED','REJECTED','WITHDRAWN')),
  FOREIGN KEY (booking_id) REFERENCES bookings(id),
  FOREIGN KEY (parent_payment_id,booking_id) REFERENCES payment_transactions(id,booking_id),
  FOREIGN KEY (payment_transaction_id,booking_id) REFERENCES payment_transactions(id,booking_id),
  FOREIGN KEY (requested_by) REFERENCES users(id),
  FOREIGN KEY (handled_by) REFERENCES users(id)
);
CREATE INDEX ix_refund_request_queue ON refund_requests(status,created_at);
CREATE INDEX ix_refund_request_booking ON refund_requests(booking_id,status);
