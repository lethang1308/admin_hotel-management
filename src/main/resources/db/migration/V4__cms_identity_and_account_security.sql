-- Incremental CMS additions. Existing migrations and account credentials are preserved.
ALTER TABLE users ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT ck_user_security_version CHECK (security_version >= 0);
ALTER TABLE customers ADD COLUMN document_type VARCHAR(30) NULL;
ALTER TABLE customers ADD COLUMN document_number_encrypted TEXT NULL;
ALTER TABLE customers ADD COLUMN document_number_hash CHAR(64) NULL;
CREATE INDEX idx_customers_document_hash ON customers (document_number_hash);

-- Backfill profiles for existing accounts without assigning any additional roles.
-- Legacy codes use a separate prefix from newly generated NV/KH numeric codes.
INSERT INTO employees (user_id, employee_code, position, created_at, updated_at)
SELECT u.id, CONCAT('NV-U', u.id), 'Staff', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM users u
WHERE EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
              WHERE ur.user_id=u.id AND r.code IN ('ADMIN','RECEPTIONIST'))
  AND NOT EXISTS (SELECT 1 FROM employees e WHERE e.user_id=u.id);

INSERT INTO customers (user_id, customer_code, full_name, email, phone, created_at, updated_at)
SELECT u.id, CONCAT('KH-U', u.id), u.full_name, u.email, u.phone, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM users u
WHERE EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
              WHERE ur.user_id=u.id AND r.code='USER')
  AND NOT EXISTS (SELECT 1 FROM customers c WHERE c.user_id=u.id);
