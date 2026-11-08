ALTER TABLE customers ADD COLUMN gender varchar(20);
ALTER TABLE customers ADD CONSTRAINT ck_customer_gender CHECK (gender IS NULL OR gender IN ('MALE','FEMALE','OTHER'));
