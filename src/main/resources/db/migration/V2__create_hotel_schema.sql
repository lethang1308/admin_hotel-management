-- Source: backend/docs/database.txt (DBML, 59 application tables).
-- Source SHA-256: 74efc99eac8949a262ea421efeb47c2d54e9a8a5dffaada523da45d9bfb73d15
-- Adds 55 hotel tables; preserves V1 and existing authentication records.
-- MySQL >= 8.0.16 (InnoDB), also exercised on H2 MySQL mode by the test suite.
-- INACTIVE remains accepted for compatibility with the existing Java API/admin UI.
-- Nullable profile columns do not require fake data for existing accounts.

ALTER TABLE users ADD COLUMN phone VARCHAR(20) NULL;
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500) NULL;
ALTER TABLE users ADD COLUMN email_verified_at DATETIME(6) NULL;
ALTER TABLE users ADD COLUMN last_login_at DATETIME(6) NULL;
ALTER TABLE users ADD COLUMN locked_until DATETIME(6) NULL;
ALTER TABLE users ADD COLUMN password_changed_at DATETIME(6) NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_phone UNIQUE (phone);
ALTER TABLE users ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE users ADD CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','LOCKED','DISABLED','INACTIVE'));

CREATE TABLE `permissions` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(100) UNIQUE NOT NULL,
  `name` varchar(150) NOT NULL,
  `description` varchar(500)
);

CREATE TABLE `role_permissions` (
  `role_id` bigint NOT NULL,
  `permission_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`, `permission_id`)
);

CREATE TABLE `account_tokens` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `purpose` varchar(30) NOT NULL,
  `token_hash` char(64) UNIQUE NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `used_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_account_token_purpose` CHECK (purpose IN ('PASSWORD_RESET','EMAIL_VERIFY'))
);

CREATE TABLE `employees` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` bigint UNIQUE NOT NULL,
  `employee_code` varchar(30) UNIQUE NOT NULL,
  `position` varchar(100),
  `hired_on` date,
  `ended_on` date,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_employee_dates` CHECK (ended_on IS NULL OR hired_on IS NULL OR ended_on >= hired_on)
);

CREATE TABLE `customers` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` bigint UNIQUE,
  `customer_code` varchar(30) UNIQUE NOT NULL,
  `full_name` varchar(150) NOT NULL,
  `email` varchar(150),
  `phone` varchar(20),
  `date_of_birth` date,
  `nationality_code` char(2),
  `address` varchar(500),
  `notes` text,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `audit_logs` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `actor_user_id` bigint,
  `action` varchar(100) NOT NULL,
  `entity_type` varchar(80) NOT NULL,
  `entity_id` varchar(80) NOT NULL,
  `before_data` json,
  `after_data` json,
  `reason` varchar(1000),
  `request_id` varchar(100),
  `ip_address` varchar(64),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `hotel_settings` (
  `id` bigint PRIMARY KEY,
  `name` varchar(200) NOT NULL,
  `description` text,
  `address` varchar(500) NOT NULL,
  `hotline` varchar(30),
  `email` varchar(150),
  `logo_url` varchar(500),
  `timezone` varchar(60) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  `currency` char(3) NOT NULL DEFAULT 'VND',
  `standard_check_in` time NOT NULL DEFAULT '14:00:00',
  `standard_check_out` time NOT NULL DEFAULT '12:00:00',
  `booking_hold_minutes` int NOT NULL DEFAULT 15,
  `default_tax_percent` decimal(5,2) NOT NULL DEFAULT 0,
  `default_deposit_policy_id` bigint NOT NULL,
  `default_cancellation_policy_id` bigint NOT NULL,
  `updated_by` bigint,
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_single_hotel` CHECK (id = 1),
  CONSTRAINT `ck_hotel_values` CHECK (booking_hold_minutes > 0 AND default_tax_percent BETWEEN 0 AND 100)
);

CREATE TABLE `system_settings` (
  `setting_key` varchar(100) PRIMARY KEY,
  `setting_value` json NOT NULL,
  `description` varchar(500),
  `updated_by` bigint,
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `floors` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(30) UNIQUE NOT NULL,
  `name` varchar(100) NOT NULL,
  `area_name` varchar(100) NOT NULL,
  `floor_number` int NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  `is_active` boolean NOT NULL DEFAULT true
);

CREATE TABLE `room_types` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(30) UNIQUE NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` text,
  `base_price` decimal(15,2) NOT NULL,
  `area_sqm` decimal(8,2),
  `max_adults` int NOT NULL,
  `max_children` int NOT NULL DEFAULT 0,
  `max_occupancy` int NOT NULL,
  `bed_description` varchar(200),
  `is_featured` boolean NOT NULL DEFAULT false,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_room_type_values` CHECK (base_price >= 0 AND max_adults > 0 AND max_children >= 0 AND max_occupancy >= max_adults),
  CONSTRAINT `ck_room_area` CHECK (area_sqm IS NULL OR area_sqm > 0)
);

CREATE TABLE `room_type_images` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `room_type_id` bigint NOT NULL,
  `image_url` varchar(500) NOT NULL,
  `alt_text` varchar(200),
  `sort_order` int NOT NULL DEFAULT 0
);

CREATE TABLE `amenities` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(50) UNIQUE NOT NULL,
  `name` varchar(100) NOT NULL,
  `icon` varchar(100),
  `is_active` boolean NOT NULL DEFAULT true
);

CREATE TABLE `room_type_amenities` (
  `room_type_id` bigint NOT NULL,
  `amenity_id` bigint NOT NULL,
  PRIMARY KEY (`room_type_id`, `amenity_id`)
);

CREATE TABLE `rooms` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `room_number` varchar(20) UNIQUE NOT NULL,
  `room_type_id` bigint NOT NULL,
  `floor_id` bigint NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'AVAILABLE',
  `sell_from` date NOT NULL,
  `sell_until` date,
  `notes` text,
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_room_status` CHECK (status IN ('AVAILABLE','OCCUPIED','DIRTY','CLEANING','MAINTENANCE','INACTIVE')),
  CONSTRAINT `ck_room_sell_dates` CHECK (sell_until IS NULL OR sell_until > sell_from)
);

CREATE TABLE `room_status_history` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `room_id` bigint NOT NULL,
  `old_status` varchar(30),
  `new_status` varchar(30) NOT NULL,
  `changed_by` bigint,
  `reason` varchar(500),
  `changed_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `work_sessions` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `employee_id` bigint NOT NULL,
  `opened_at` datetime(6) NOT NULL,
  `closed_at` datetime(6),
  `opening_cash` decimal(15,2) NOT NULL DEFAULT 0,
  `counted_closing_cash` decimal(15,2),
  `notes` text,
  CONSTRAINT `ck_work_session_dates` CHECK (closed_at IS NULL OR closed_at > opened_at),
  CONSTRAINT `ck_work_session_cash` CHECK (opening_cash >= 0 AND (counted_closing_cash IS NULL OR counted_closing_cash >= 0))
);

CREATE TABLE `maintenance_requests` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `room_id` bigint NOT NULL,
  `reported_by` bigint NOT NULL,
  `assigned_employee_id` bigint,
  `title` varchar(200) NOT NULL,
  `description` text,
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `status` varchar(30) NOT NULL DEFAULT 'OPEN',
  `started_at` datetime(6),
  `resolved_at` datetime(6),
  `resolution` text,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_maintenance_status` CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CANCELLED')),
  CONSTRAINT `ck_maintenance_priority` CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
  CONSTRAINT `ck_maintenance_times` CHECK (resolved_at IS NULL OR started_at IS NULL OR resolved_at >= started_at)
);

CREATE TABLE `room_blocks` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `room_id` bigint NOT NULL,
  `maintenance_request_id` bigint,
  `block_type` varchar(30) NOT NULL,
  `from_date` date NOT NULL,
  `to_date` date NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `reason` varchar(500) NOT NULL,
  `created_by` bigint NOT NULL,
  `released_by` bigint,
  `released_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_room_block_dates` CHECK (to_date > from_date),
  CONSTRAINT `ck_room_block_types` CHECK (block_type IN ('MAINTENANCE','OUT_OF_SERVICE') AND status IN ('ACTIVE','RELEASED','CANCELLED'))
);

CREATE TABLE `housekeeping_tasks` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `room_id` bigint NOT NULL,
  `assigned_employee_id` bigint,
  `created_by` bigint,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `notes` text,
  `started_at` datetime(6),
  `completed_at` datetime(6),
  `verified_by` bigint,
  `verified_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_housekeeping_status` CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','VERIFIED','CANCELLED')),
  CONSTRAINT `ck_housekeeping_times` CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE `deposit_policies` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(30) NOT NULL,
  `version` int NOT NULL DEFAULT 1,
  `name` varchar(150) NOT NULL,
  `amount_type` varchar(20) NOT NULL,
  `amount_value` decimal(15,2) NOT NULL DEFAULT 0,
  `payment_due_minutes` int NOT NULL DEFAULT 15,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_deposit_values` CHECK (amount_type IN ('NONE','PERCENT','FIXED') AND amount_value >= 0 AND payment_due_minutes > 0),
  CONSTRAINT `ck_deposit_percent` CHECK (amount_type <> 'PERCENT' OR amount_value <= 100),
  CONSTRAINT `ck_deposit_none` CHECK (amount_type <> 'NONE' OR amount_value = 0)
);

CREATE TABLE `cancellation_policies` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(30) NOT NULL,
  `version` int NOT NULL DEFAULT 1,
  `name` varchar(150) NOT NULL,
  `description` text,
  `customer_can_cancel` boolean NOT NULL DEFAULT true,
  `customer_cancel_deadline_hours` int NOT NULL DEFAULT 0,
  `no_show_fee_type` varchar(20) NOT NULL DEFAULT 'PERCENT',
  `no_show_fee_value` decimal(15,2) NOT NULL DEFAULT 100,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_cancel_policy_values` CHECK (customer_cancel_deadline_hours >= 0 AND no_show_fee_value >= 0),
  CONSTRAINT `ck_no_show_fee_type` CHECK (no_show_fee_type IN ('NONE','PERCENT','FIXED','FIRST_NIGHT')),
  CONSTRAINT `ck_no_show_percent` CHECK (no_show_fee_type <> 'PERCENT' OR no_show_fee_value <= 100)
);

CREATE TABLE `cancellation_policy_rules` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `cancellation_policy_id` bigint NOT NULL,
  `min_hours_before_checkin` int NOT NULL,
  `fee_type` varchar(20) NOT NULL,
  `fee_value` decimal(15,2) NOT NULL DEFAULT 0,
  CONSTRAINT `ck_cancel_rule_values` CHECK (min_hours_before_checkin >= 0 AND fee_value >= 0 AND fee_type IN ('NONE','PERCENT','FIXED','FIRST_NIGHT')),
  CONSTRAINT `ck_cancel_rule_percent` CHECK (fee_type <> 'PERCENT' OR fee_value <= 100),
  CONSTRAINT `ck_cancel_rule_none` CHECK (fee_type <> 'NONE' OR fee_value = 0)
);

CREATE TABLE `surcharge_policies` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(30) NOT NULL,
  `version` int NOT NULL DEFAULT 1,
  `name` varchar(150) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `room_type_id` bigint,
  `from_minutes` int,
  `to_minutes` int,
  `amount_type` varchar(20) NOT NULL,
  `amount_value` decimal(15,2) NOT NULL,
  `priority` int NOT NULL DEFAULT 0,
  `is_active` boolean NOT NULL DEFAULT true,
  CONSTRAINT `ck_surcharge_values` CHECK (kind IN ('EARLY_CHECKIN','LATE_CHECKOUT','EXTRA_GUEST','EXTRA_BED') AND amount_type IN ('FIXED','PERCENT_NIGHT','PER_HOUR') AND amount_value >= 0),
  CONSTRAINT `ck_surcharge_min` CHECK (from_minutes IS NULL OR from_minutes >= 0),
  CONSTRAINT `ck_surcharge_range` CHECK (to_minutes IS NULL OR (from_minutes IS NOT NULL AND to_minutes > from_minutes))
);

CREATE TABLE `rate_rules` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(40) NOT NULL,
  `version` int NOT NULL DEFAULT 1,
  `name` varchar(150) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `start_date` date NOT NULL,
  `end_date` date NOT NULL,
  `weekday_mask` int NOT NULL DEFAULT 127,
  `min_nights` int NOT NULL DEFAULT 1,
  `max_nights` int,
  `value_type` varchar(20) NOT NULL,
  `value` decimal(15,2) NOT NULL,
  `priority` int NOT NULL DEFAULT 0,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_rate_dates_nights` CHECK (end_date > start_date AND min_nights > 0 AND (max_nights IS NULL OR max_nights >= min_nights)),
  CONSTRAINT `ck_rate_values` CHECK (weekday_mask BETWEEN 1 AND 127 AND value >= 0),
  CONSTRAINT `ck_rate_kind` CHECK ((kind = 'NIGHT_OVERRIDE' AND value_type = 'FIXED') OR (kind = 'LENGTH_DISCOUNT' AND value_type IN ('PERCENT','FIXED'))),
  CONSTRAINT `ck_rate_percent` CHECK (value_type <> 'PERCENT' OR value <= 100)
);

CREATE TABLE `rate_rule_room_types` (
  `rate_rule_id` bigint NOT NULL,
  `room_type_id` bigint NOT NULL,
  PRIMARY KEY (`rate_rule_id`, `room_type_id`)
);

CREATE TABLE `vouchers` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(50) UNIQUE NOT NULL,
  `name` varchar(150) NOT NULL,
  `description` text,
  `discount_type` varchar(20) NOT NULL,
  `discount_value` decimal(15,2) NOT NULL,
  `max_discount_amount` decimal(15,2),
  `min_room_subtotal` decimal(15,2) NOT NULL DEFAULT 0,
  `starts_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `stay_from` date,
  `stay_until` date,
  `max_uses` int,
  `max_uses_per_customer` int NOT NULL DEFAULT 1,
  `combinable_with_length_discount` boolean NOT NULL DEFAULT true,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_by` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_voucher_values` CHECK (expires_at > starts_at AND discount_value > 0 AND min_room_subtotal >= 0),
  CONSTRAINT `ck_voucher_type` CHECK (discount_type IN ('PERCENT','FIXED') AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
  CONSTRAINT `ck_voucher_limits` CHECK ((max_uses IS NULL OR max_uses > 0) AND max_uses_per_customer > 0 AND (max_discount_amount IS NULL OR max_discount_amount > 0)),
  CONSTRAINT `ck_voucher_stay_dates` CHECK (stay_until IS NULL OR (stay_from IS NOT NULL AND stay_until > stay_from))
);

CREATE TABLE `voucher_room_types` (
  `voucher_id` bigint NOT NULL,
  `room_type_id` bigint NOT NULL,
  PRIMARY KEY (`voucher_id`, `room_type_id`)
);

CREATE TABLE `bookings` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_code` varchar(30) UNIQUE NOT NULL,
  `customer_id` bigint NOT NULL,
  `created_by` bigint,
  `source` varchar(20) NOT NULL,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `contact_name` varchar(150) NOT NULL,
  `contact_email` varchar(150),
  `contact_phone` varchar(20) NOT NULL,
  `special_requests` text,
  `internal_notes` text,
  `deposit_policy_id` bigint NOT NULL,
  `cancellation_policy_id` bigint NOT NULL,
  `policy_snapshot` json NOT NULL,
  `currency` char(3) NOT NULL DEFAULT 'VND',
  `deposit_required_amount` decimal(15,2) NOT NULL DEFAULT 0,
  `deposit_due_at` datetime(6),
  `hold_expires_at` datetime(6),
  `confirmed_by` bigint,
  `confirmed_at` datetime(6),
  `rejected_by` bigint,
  `rejected_at` datetime(6),
  `rejection_reason` varchar(1000),
  `rebooked_from_id` bigint,
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_booking_status` CHECK (status IN ('PENDING','CONFIRMED','CHECKED_IN','CHECKED_OUT','CANCELLED','REJECTED','NO_SHOW')),
  CONSTRAINT `ck_booking_values` CHECK (source IN ('ONLINE','WALK_IN','PHONE') AND deposit_required_amount >= 0)
);

CREATE TABLE `booking_rooms` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `room_type_id` bigint NOT NULL,
  `room_type_name_snapshot` varchar(100) NOT NULL,
  `check_in_date` date NOT NULL,
  `check_out_date` date NOT NULL,
  `expected_check_in_at` datetime(6) NOT NULL,
  `expected_check_out_at` datetime(6) NOT NULL,
  `adults` int NOT NULL,
  `children` int NOT NULL DEFAULT 0,
  `status` varchar(30) NOT NULL DEFAULT 'PENDING',
  `actual_check_in_at` datetime(6),
  `actual_check_out_at` datetime(6),
  `checked_in_by` bigint,
  `checked_out_by` bigint,
  `special_requests` text,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_booking_room_dates` CHECK (check_out_date > check_in_date AND expected_check_out_at > expected_check_in_at),
  CONSTRAINT `ck_booking_room_guests` CHECK (adults > 0 AND children >= 0 AND line_number > 0),
  CONSTRAINT `ck_booking_room_status` CHECK (status IN ('PENDING','CONFIRMED','CHECKED_IN','CHECKED_OUT','CANCELLED','REJECTED','NO_SHOW')),
  CONSTRAINT `ck_booking_actual_times` CHECK (actual_check_out_at IS NULL OR (actual_check_in_at IS NOT NULL AND actual_check_out_at >= actual_check_in_at))
);

CREATE TABLE `room_type_inventory` (
  `room_type_id` bigint NOT NULL,
  `stay_date` date NOT NULL,
  `total_rooms` int NOT NULL,
  `blocked_rooms` int NOT NULL DEFAULT 0,
  `sellable_rooms` int NOT NULL,
  `held_rooms` int NOT NULL DEFAULT 0,
  `reserved_rooms` int NOT NULL DEFAULT 0,
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_inventory_capacity` CHECK (total_rooms >= 0 AND blocked_rooms >= 0 AND blocked_rooms <= total_rooms AND sellable_rooms = total_rooms - blocked_rooms),
  CONSTRAINT `ck_inventory_available` CHECK (held_rooms >= 0 AND reserved_rooms >= 0 AND held_rooms + reserved_rooms <= sellable_rooms),
  PRIMARY KEY (`room_type_id`, `stay_date`)
);

CREATE TABLE `booking_room_nights` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_room_id` bigint NOT NULL,
  `stay_date` date NOT NULL,
  `inventory_room_type_id` bigint NOT NULL,
  `rate_rule_id` bigint,
  `length_discount_rule_id` bigint,
  `base_price_snapshot` decimal(15,2) NOT NULL,
  `nightly_rate` decimal(15,2) NOT NULL,
  `length_discount_amount` decimal(15,2) NOT NULL DEFAULT 0,
  `voucher_discount_amount` decimal(15,2) NOT NULL DEFAULT 0,
  `net_room_amount` decimal(15,2) NOT NULL,
  `inventory_state` varchar(20) NOT NULL DEFAULT 'HELD',
  `stay_status` varchar(20) NOT NULL DEFAULT 'PLANNED',
  `recognized_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_night_money_nonnegative` CHECK (base_price_snapshot >= 0 AND nightly_rate >= 0 AND length_discount_amount >= 0 AND voucher_discount_amount >= 0 AND net_room_amount >= 0),
  CONSTRAINT `ck_night_money_sum` CHECK (net_room_amount = nightly_rate - length_discount_amount - voucher_discount_amount),
  CONSTRAINT `ck_night_states` CHECK (inventory_state IN ('HELD','RESERVED','RELEASED') AND stay_status IN ('PLANNED','STAYED','CANCELLED','NO_SHOW'))
);

CREATE TABLE `room_allocations` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_room_id` bigint NOT NULL,
  `room_id` bigint NOT NULL,
  `from_date` date NOT NULL,
  `to_date` date NOT NULL,
  `occupied_from_at` datetime(6),
  `occupied_until_at` datetime(6),
  `status` varchar(20) NOT NULL DEFAULT 'SCHEDULED',
  `assigned_by` bigint,
  `ended_by` bigint,
  `reason` varchar(500),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_allocation_dates` CHECK (to_date > from_date),
  CONSTRAINT `ck_allocation_status` CHECK (status IN ('SCHEDULED','OCCUPIED','COMPLETED','RELEASED')),
  CONSTRAINT `ck_allocation_actual_times` CHECK (occupied_until_at IS NULL OR (occupied_from_at IS NOT NULL AND occupied_until_at >= occupied_from_at))
);

CREATE TABLE `room_night_claims` (
  `room_id` bigint NOT NULL,
  `stay_date` date NOT NULL,
  `room_allocation_id` bigint,
  `room_block_id` bigint,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_claim_one_owner` CHECK ((room_allocation_id IS NOT NULL AND room_block_id IS NULL) OR (room_allocation_id IS NULL AND room_block_id IS NOT NULL)),
  PRIMARY KEY (`room_id`, `stay_date`)
);

CREATE TABLE `booking_guests` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `customer_id` bigint,
  `full_name` varchar(150) NOT NULL,
  `date_of_birth` date,
  `guest_type` varchar(20) NOT NULL DEFAULT 'ADULT',
  `nationality_code` char(2),
  `phone` varchar(20),
  `document_type` varchar(30),
  `document_number_encrypted` text,
  `document_number_hash` char(64),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_guest_type` CHECK (guest_type IN ('ADULT','CHILD'))
);

CREATE TABLE `stay_guests` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint NOT NULL,
  `booking_guest_id` bigint NOT NULL,
  `room_allocation_id` bigint NOT NULL,
  `arrived_at` datetime(6) NOT NULL,
  `departed_at` datetime(6),
  CONSTRAINT `ck_stay_guest_times` CHECK (departed_at IS NULL OR departed_at >= arrived_at)
);

CREATE TABLE `booking_status_history` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint,
  `old_status` varchar(30),
  `new_status` varchar(30) NOT NULL,
  `changed_by` bigint,
  `reason` varchar(1000),
  `changed_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `booking_change_requests` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint,
  `requested_by` bigint NOT NULL,
  `change_type` varchar(30) NOT NULL,
  `before_snapshot` json NOT NULL,
  `requested_changes` json NOT NULL,
  `reason` varchar(1000),
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `handled_by` bigint,
  `handled_at` datetime(6),
  `response_note` varchar(1000),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_change_request_status` CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT `ck_change_request_type` CHECK (change_type IN ('CHANGE_DATES','EXTEND','SHORTEN','CHANGE_ROOM'))
);

CREATE TABLE `booking_cancellations` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint,
  `kind` varchar(20) NOT NULL,
  `requested_by` bigint,
  `processed_by` bigint,
  `reason` varchar(1000) NOT NULL,
  `policy_snapshot` json NOT NULL,
  `cancelled_room_amount` decimal(15,2) NOT NULL,
  `cancellation_fee` decimal(15,2) NOT NULL DEFAULT 0,
  `proposed_refund_amount` decimal(15,2) NOT NULL DEFAULT 0,
  `processed_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_cancellation_values` CHECK (kind IN ('CANCEL','NO_SHOW','EXPIRED') AND cancelled_room_amount >= 0 AND cancellation_fee >= 0 AND proposed_refund_amount >= 0)
);

CREATE TABLE `booking_qr_tokens` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `token_hash` char(64) UNIQUE NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6),
  `last_scanned_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `voucher_redemptions` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `voucher_id` bigint NOT NULL,
  `booking_id` bigint UNIQUE NOT NULL,
  `customer_id` bigint NOT NULL,
  `discount_amount` decimal(15,2) NOT NULL,
  `voucher_snapshot` json NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'RESERVED',
  `reserved_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `redeemed_at` datetime(6),
  `released_at` datetime(6),
  CONSTRAINT `ck_redemption_values` CHECK (discount_amount >= 0 AND status IN ('RESERVED','REDEEMED','RELEASED'))
);

CREATE TABLE `service_categories` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `code` varchar(30) UNIQUE NOT NULL,
  `name` varchar(100) NOT NULL,
  `is_active` boolean NOT NULL DEFAULT true
);

CREATE TABLE `services` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `category_id` bigint NOT NULL,
  `code` varchar(40) UNIQUE NOT NULL,
  `name` varchar(150) NOT NULL,
  `description` text,
  `unit` varchar(40) NOT NULL,
  `unit_price` decimal(15,2) NOT NULL,
  `tax_percent` decimal(5,2) NOT NULL DEFAULT 0,
  `is_active` boolean NOT NULL DEFAULT true,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_service_values` CHECK (unit_price >= 0 AND tax_percent BETWEEN 0 AND 100)
);

CREATE TABLE `service_usages` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint,
  `room_allocation_id` bigint,
  `service_id` bigint NOT NULL,
  `service_name_snapshot` varchar(150) NOT NULL,
  `unit_snapshot` varchar(40) NOT NULL,
  `quantity` decimal(10,3) NOT NULL,
  `unit_price_snapshot` decimal(15,2) NOT NULL,
  `tax_percent_snapshot` decimal(5,2) NOT NULL DEFAULT 0,
  `status` varchar(20) NOT NULL DEFAULT 'ACTIVE',
  `used_at` datetime(6) NOT NULL,
  `recorded_by` bigint NOT NULL,
  `voided_by` bigint,
  `voided_at` datetime(6),
  `void_reason` varchar(500),
  `notes` varchar(500),
  CONSTRAINT `ck_usage_values` CHECK (quantity > 0 AND unit_price_snapshot >= 0 AND tax_percent_snapshot BETWEEN 0 AND 100),
  CONSTRAINT `ck_usage_status` CHECK (status IN ('ACTIVE','VOIDED') AND (room_allocation_id IS NULL OR booking_room_id IS NOT NULL))
);

CREATE TABLE `booking_charges` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint,
  `booking_room_night_id` bigint,
  `service_usage_id` bigint,
  `cancellation_id` bigint,
  `surcharge_policy_id` bigint,
  `adjusts_charge_id` bigint,
  `source_key` varchar(150) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `description` varchar(500) NOT NULL,
  `service_date` date NOT NULL,
  `quantity` decimal(10,3) NOT NULL DEFAULT 1,
  `unit_price` decimal(15,2) NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `tax_percent_snapshot` decimal(5,2),
  `policy_snapshot` json,
  `status` varchar(20) NOT NULL DEFAULT 'POSTED',
  `created_by` bigint,
  `voided_by` bigint,
  `voided_at` datetime(6),
  `void_reason` varchar(500),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_charge_amount` CHECK (quantity > 0 AND amount = ROUND(quantity * unit_price, 2)),
  CONSTRAINT `ck_charge_status_kind` CHECK (kind IN ('ROOM','SERVICE','SURCHARGE','DISCOUNT','TAX','CANCELLATION','ROOM_ADJUSTMENT','ADJUSTMENT') AND status IN ('POSTED','VOIDED')),
  CONSTRAINT `ck_charge_night_owner` CHECK (booking_room_night_id IS NULL OR booking_room_id IS NOT NULL),
  CONSTRAINT `ck_room_charge_source` CHECK (kind NOT IN ('ROOM','ROOM_ADJUSTMENT') OR booking_room_night_id IS NOT NULL),
  CONSTRAINT `ck_service_charge_source` CHECK (kind <> 'SERVICE' OR service_usage_id IS NOT NULL),
  CONSTRAINT `ck_charge_tax` CHECK (tax_percent_snapshot IS NULL OR tax_percent_snapshot BETWEEN 0 AND 100)
);

CREATE TABLE `payment_transactions` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `transaction_code` varchar(40) UNIQUE NOT NULL,
  `booking_id` bigint NOT NULL,
  `parent_payment_id` bigint,
  `kind` varchar(20) NOT NULL,
  `method` varchar(30) NOT NULL,
  `provider` varchar(50) NOT NULL,
  `provider_transaction_id` varchar(150),
  `idempotency_key` varchar(100) UNIQUE NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `currency` char(3) NOT NULL DEFAULT 'VND',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `work_session_id` bigint,
  `created_by` bigint,
  `approved_by` bigint,
  `reason` varchar(1000),
  `paid_at` datetime(6),
  `expires_at` datetime(6),
  `failure_reason` varchar(1000),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_payment_values` CHECK (amount > 0 AND kind IN ('DEPOSIT','PAYMENT','REFUND')),
  CONSTRAINT `ck_payment_status` CHECK (method IN ('CASH','BANK_TRANSFER','ONLINE_GATEWAY') AND status IN ('PENDING','SUCCEEDED','FAILED','CANCELLED')),
  CONSTRAINT `ck_refund_parent` CHECK ((kind = 'REFUND' AND parent_payment_id IS NOT NULL) OR (kind <> 'REFUND' AND parent_payment_id IS NULL))
);

CREATE TABLE `payment_webhook_events` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `provider` varchar(50) NOT NULL,
  `provider_event_id` varchar(200) NOT NULL,
  `payment_transaction_id` bigint,
  `payload_hash` char(64) NOT NULL,
  `redacted_payload` json,
  `signature_valid` boolean NOT NULL,
  `processing_status` varchar(20) NOT NULL DEFAULT 'RECEIVED',
  `error_message` varchar(1000),
  `received_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `processed_at` datetime(6),
  CONSTRAINT `ck_webhook_status` CHECK (processing_status IN ('RECEIVED','PROCESSED','IGNORED','FAILED'))
);

CREATE TABLE `invoices` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `invoice_number` varchar(40) UNIQUE NOT NULL,
  `booking_id` bigint NOT NULL,
  `revision` int NOT NULL DEFAULT 1,
  `supersedes_invoice_id` bigint UNIQUE,
  `status` varchar(20) NOT NULL DEFAULT 'DRAFT',
  `seller_snapshot` json NOT NULL,
  `buyer_name` varchar(200) NOT NULL,
  `buyer_email` varchar(150),
  `buyer_address` varchar(500),
  `buyer_tax_code` varchar(30),
  `currency` char(3) NOT NULL DEFAULT 'VND',
  `subtotal_amount` decimal(15,2) NOT NULL,
  `tax_amount` decimal(15,2) NOT NULL DEFAULT 0,
  `total_amount` decimal(15,2) NOT NULL,
  `issued_by` bigint,
  `issued_at` datetime(6),
  `voided_by` bigint,
  `voided_at` datetime(6),
  `void_reason` varchar(1000),
  `pdf_storage_key` varchar(500),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_invoice_values` CHECK (revision > 0 AND status IN ('DRAFT','ISSUED','VOID') AND total_amount = subtotal_amount + tax_amount)
);

CREATE TABLE `invoice_lines` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `invoice_id` bigint NOT NULL,
  `booking_id` bigint NOT NULL,
  `booking_charge_id` bigint NOT NULL,
  `line_number` int NOT NULL,
  `kind` varchar(30) NOT NULL,
  `description` varchar(500) NOT NULL,
  `service_date` date NOT NULL,
  `quantity` decimal(10,3) NOT NULL,
  `unit_price` decimal(15,2) NOT NULL,
  `amount` decimal(15,2) NOT NULL,
  `details_snapshot` json,
  CONSTRAINT `ck_invoice_line_values` CHECK (line_number > 0 AND quantity > 0 AND amount = ROUND(quantity * unit_price, 2))
);

CREATE TABLE `outbox_events` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `event_key` varchar(150) UNIQUE NOT NULL,
  `event_type` varchar(100) NOT NULL,
  `aggregate_type` varchar(50) NOT NULL,
  `aggregate_id` bigint NOT NULL,
  `payload` json NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT 0,
  `available_at` datetime(6) NOT NULL,
  `locked_until` datetime(6),
  `worker_id` varchar(100),
  `processed_at` datetime(6),
  `last_error` varchar(1000),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_outbox_values` CHECK (status IN ('PENDING','PROCESSING','PROCESSED','FAILED') AND attempts >= 0)
);

CREATE TABLE `notifications` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `booking_id` bigint,
  `outbox_event_id` bigint,
  `deduplication_key` varchar(200) UNIQUE NOT NULL,
  `type` varchar(60) NOT NULL,
  `title` varchar(200) NOT NULL,
  `body` text NOT NULL,
  `read_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE TABLE `notification_deliveries` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `notification_id` bigint,
  `outbox_event_id` bigint NOT NULL,
  `channel` varchar(20) NOT NULL,
  `recipient_address` varchar(200) NOT NULL,
  `deduplication_key` varchar(200) UNIQUE NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT 0,
  `provider_message_id` varchar(150),
  `sent_at` datetime(6),
  `next_retry_at` datetime(6),
  `last_error` varchar(1000),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_delivery_values` CHECK (channel IN ('IN_APP','EMAIL','WEBSOCKET') AND status IN ('PENDING','SENT','FAILED') AND attempts >= 0)
);

CREATE TABLE `favorites` (
  `customer_id` bigint NOT NULL,
  `room_type_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  PRIMARY KEY (`customer_id`, `room_type_id`)
);

CREATE TABLE `reviews` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `booking_id` bigint NOT NULL,
  `booking_room_id` bigint UNIQUE NOT NULL,
  `customer_id` bigint NOT NULL,
  `rating` int NOT NULL,
  `comment` text,
  `status` varchar(20) NOT NULL DEFAULT 'PUBLISHED',
  `moderated_by` bigint,
  `moderated_at` datetime(6),
  `moderation_reason` varchar(500),
  `reply` text,
  `replied_by` bigint,
  `replied_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_review_values` CHECK (rating BETWEEN 1 AND 5 AND status IN ('PENDING','PUBLISHED','HIDDEN'))
);

CREATE TABLE `support_tickets` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `ticket_code` varchar(30) UNIQUE NOT NULL,
  `customer_id` bigint NOT NULL,
  `booking_id` bigint,
  `subject` varchar(200) NOT NULL,
  `category` varchar(30) NOT NULL,
  `priority` varchar(20) NOT NULL DEFAULT 'NORMAL',
  `status` varchar(30) NOT NULL DEFAULT 'OPEN',
  `assigned_employee_id` bigint,
  `resolved_at` datetime(6),
  `closed_at` datetime(6),
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
  CONSTRAINT `ck_ticket_values` CHECK (category IN ('BOOKING','PAYMENT','ROOM','OTHER') AND priority IN ('LOW','NORMAL','HIGH','URGENT')),
  CONSTRAINT `ck_ticket_status` CHECK (status IN ('OPEN','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED'))
);

CREATE TABLE `support_messages` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `ticket_id` bigint NOT NULL,
  `sender_user_id` bigint NOT NULL,
  `body` text NOT NULL,
  `is_internal` boolean NOT NULL DEFAULT false,
  `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6))
);

CREATE INDEX `role_permissions_index_4` ON `role_permissions` (`permission_id`);

CREATE INDEX `account_tokens_index_8` ON `account_tokens` (`user_id`, `purpose`);

CREATE INDEX `account_tokens_index_9` ON `account_tokens` (`expires_at`);

CREATE INDEX `customers_index_10` ON `customers` (`phone`);

CREATE INDEX `customers_index_11` ON `customers` (`email`);

CREATE INDEX `customers_index_12` ON `customers` (`full_name`);

CREATE INDEX `customers_index_13` ON `customers` (`created_at`);

CREATE INDEX `audit_logs_index_14` ON `audit_logs` (`entity_type`, `entity_id`, `created_at`);

CREATE INDEX `audit_logs_index_15` ON `audit_logs` (`actor_user_id`, `created_at`);

CREATE INDEX `audit_logs_index_16` ON `audit_logs` (`created_at`);

CREATE UNIQUE INDEX `room_type_images_index_17` ON `room_type_images` (`room_type_id`, `sort_order`);

CREATE INDEX `room_type_amenities_index_18` ON `room_type_amenities` (`amenity_id`);

CREATE INDEX `rooms_index_19` ON `rooms` (`room_type_id`, `status`);

CREATE INDEX `rooms_index_20` ON `rooms` (`floor_id`);

CREATE INDEX `room_status_history_index_21` ON `room_status_history` (`room_id`, `changed_at`);

CREATE INDEX `work_sessions_index_22` ON `work_sessions` (`employee_id`, `opened_at`);

CREATE INDEX `maintenance_requests_index_23` ON `maintenance_requests` (`room_id`, `status`);

CREATE INDEX `maintenance_requests_index_24` ON `maintenance_requests` (`status`, `created_at`);

CREATE UNIQUE INDEX `maintenance_requests_index_25` ON `maintenance_requests` (`id`, `room_id`);

CREATE INDEX `room_blocks_index_26` ON `room_blocks` (`room_id`, `from_date`, `to_date`);

CREATE UNIQUE INDEX `room_blocks_index_27` ON `room_blocks` (`id`, `room_id`);

CREATE INDEX `housekeeping_tasks_index_28` ON `housekeeping_tasks` (`room_id`, `status`);

CREATE INDEX `housekeeping_tasks_index_29` ON `housekeeping_tasks` (`status`, `created_at`);

CREATE UNIQUE INDEX `deposit_policies_index_30` ON `deposit_policies` (`code`, `version`);

CREATE UNIQUE INDEX `cancellation_policies_index_31` ON `cancellation_policies` (`code`, `version`);

CREATE UNIQUE INDEX `cancellation_policy_rules_index_32` ON `cancellation_policy_rules` (`cancellation_policy_id`, `min_hours_before_checkin`);

CREATE UNIQUE INDEX `surcharge_policies_index_33` ON `surcharge_policies` (`code`, `version`);

CREATE INDEX `surcharge_policies_index_34` ON `surcharge_policies` (`kind`, `room_type_id`, `is_active`);

CREATE UNIQUE INDEX `rate_rules_index_35` ON `rate_rules` (`code`, `version`);

CREATE INDEX `rate_rules_index_36` ON `rate_rules` (`kind`, `is_active`, `start_date`, `end_date`);

CREATE INDEX `rate_rule_room_types_index_37` ON `rate_rule_room_types` (`room_type_id`);

CREATE INDEX `voucher_room_types_index_38` ON `voucher_room_types` (`room_type_id`);

CREATE INDEX `bookings_index_39` ON `bookings` (`customer_id`, `created_at`);

CREATE INDEX `bookings_index_40` ON `bookings` (`status`, `created_at`);

CREATE INDEX `bookings_index_41` ON `bookings` (`status`, `hold_expires_at`);

CREATE INDEX `bookings_index_42` ON `bookings` (`contact_phone`);

CREATE UNIQUE INDEX `bookings_index_43` ON `bookings` (`id`, `customer_id`);

CREATE UNIQUE INDEX `booking_rooms_index_44` ON `booking_rooms` (`booking_id`, `line_number`);

CREATE UNIQUE INDEX `booking_rooms_index_45` ON `booking_rooms` (`id`, `booking_id`);

CREATE INDEX `booking_rooms_index_46` ON `booking_rooms` (`status`, `check_in_date`);

CREATE INDEX `booking_rooms_index_47` ON `booking_rooms` (`status`, `check_out_date`);

CREATE INDEX `booking_rooms_index_48` ON `booking_rooms` (`room_type_id`, `check_in_date`, `check_out_date`);

CREATE INDEX `room_type_inventory_index_49` ON `room_type_inventory` (`stay_date`);

CREATE UNIQUE INDEX `booking_room_nights_index_50` ON `booking_room_nights` (`booking_room_id`, `stay_date`);

CREATE INDEX `booking_room_nights_index_51` ON `booking_room_nights` (`inventory_room_type_id`, `stay_date`);

CREATE INDEX `booking_room_nights_index_52` ON `booking_room_nights` (`stay_date`, `stay_status`);

CREATE UNIQUE INDEX `booking_room_nights_index_53` ON `booking_room_nights` (`id`, `booking_room_id`);

CREATE INDEX `room_allocations_index_54` ON `room_allocations` (`room_id`, `from_date`, `to_date`);

CREATE INDEX `room_allocations_index_55` ON `room_allocations` (`booking_room_id`, `from_date`);

CREATE UNIQUE INDEX `room_allocations_index_56` ON `room_allocations` (`id`, `room_id`);

CREATE UNIQUE INDEX `room_allocations_index_57` ON `room_allocations` (`id`, `booking_room_id`);

CREATE INDEX `room_night_claims_index_58` ON `room_night_claims` (`room_allocation_id`);

CREATE INDEX `room_night_claims_index_59` ON `room_night_claims` (`room_block_id`);

CREATE INDEX `booking_guests_index_60` ON `booking_guests` (`booking_id`, `full_name`);

CREATE UNIQUE INDEX `booking_guests_index_61` ON `booking_guests` (`id`, `booking_id`);

CREATE INDEX `booking_guests_index_62` ON `booking_guests` (`document_number_hash`);

CREATE UNIQUE INDEX `stay_guests_index_63` ON `stay_guests` (`room_allocation_id`, `booking_guest_id`, `arrived_at`);

CREATE INDEX `stay_guests_index_64` ON `stay_guests` (`booking_guest_id`, `arrived_at`);

CREATE INDEX `booking_status_history_index_65` ON `booking_status_history` (`booking_id`, `changed_at`);

CREATE INDEX `booking_status_history_index_66` ON `booking_status_history` (`booking_room_id`, `changed_at`);

CREATE INDEX `booking_change_requests_index_67` ON `booking_change_requests` (`booking_id`, `created_at`);

CREATE INDEX `booking_change_requests_index_68` ON `booking_change_requests` (`status`, `created_at`);

CREATE INDEX `booking_cancellations_index_69` ON `booking_cancellations` (`booking_id`, `processed_at`);

CREATE UNIQUE INDEX `booking_cancellations_index_70` ON `booking_cancellations` (`id`, `booking_id`);

CREATE INDEX `booking_qr_tokens_index_71` ON `booking_qr_tokens` (`booking_id`);

CREATE INDEX `voucher_redemptions_index_72` ON `voucher_redemptions` (`voucher_id`, `customer_id`, `status`);

CREATE INDEX `voucher_redemptions_index_73` ON `voucher_redemptions` (`voucher_id`, `status`);

CREATE INDEX `service_usages_index_74` ON `service_usages` (`booking_id`, `used_at`);

CREATE INDEX `service_usages_index_75` ON `service_usages` (`service_id`, `used_at`);

CREATE UNIQUE INDEX `service_usages_index_76` ON `service_usages` (`id`, `booking_id`);

CREATE UNIQUE INDEX `booking_charges_index_77` ON `booking_charges` (`booking_id`, `source_key`);

CREATE UNIQUE INDEX `booking_charges_index_78` ON `booking_charges` (`id`, `booking_id`);

CREATE INDEX `booking_charges_index_79` ON `booking_charges` (`kind`, `service_date`, `status`);

CREATE INDEX `booking_charges_index_80` ON `booking_charges` (`booking_id`, `status`);

CREATE UNIQUE INDEX `payment_transactions_index_81` ON `payment_transactions` (`provider`, `provider_transaction_id`);

CREATE INDEX `payment_transactions_index_82` ON `payment_transactions` (`booking_id`, `status`);

CREATE INDEX `payment_transactions_index_83` ON `payment_transactions` (`status`, `expires_at`);

CREATE INDEX `payment_transactions_index_84` ON `payment_transactions` (`kind`, `status`, `paid_at`);

CREATE UNIQUE INDEX `payment_transactions_index_85` ON `payment_transactions` (`id`, `booking_id`);

CREATE UNIQUE INDEX `payment_webhook_events_index_86` ON `payment_webhook_events` (`provider`, `provider_event_id`);

CREATE INDEX `payment_webhook_events_index_87` ON `payment_webhook_events` (`processing_status`, `received_at`);

CREATE UNIQUE INDEX `invoices_index_88` ON `invoices` (`booking_id`, `revision`);

CREATE UNIQUE INDEX `invoices_index_89` ON `invoices` (`id`, `booking_id`);

CREATE INDEX `invoices_index_90` ON `invoices` (`status`, `issued_at`);

CREATE UNIQUE INDEX `invoice_lines_index_91` ON `invoice_lines` (`invoice_id`, `line_number`);

CREATE UNIQUE INDEX `invoice_lines_index_92` ON `invoice_lines` (`invoice_id`, `booking_charge_id`);

CREATE INDEX `outbox_events_index_93` ON `outbox_events` (`status`, `available_at`);

CREATE INDEX `outbox_events_index_94` ON `outbox_events` (`locked_until`);

CREATE INDEX `outbox_events_index_95` ON `outbox_events` (`aggregate_type`, `aggregate_id`);

CREATE INDEX `notifications_index_96` ON `notifications` (`user_id`, `read_at`, `created_at`);

CREATE INDEX `notifications_index_97` ON `notifications` (`booking_id`);

CREATE INDEX `notification_deliveries_index_98` ON `notification_deliveries` (`status`, `next_retry_at`);

CREATE INDEX `favorites_index_99` ON `favorites` (`room_type_id`);

CREATE INDEX `reviews_index_100` ON `reviews` (`status`, `created_at`);

CREATE INDEX `support_tickets_index_101` ON `support_tickets` (`customer_id`, `created_at`);

CREATE INDEX `support_tickets_index_102` ON `support_tickets` (`status`, `priority`, `created_at`);

CREATE INDEX `support_messages_index_103` ON `support_messages` (`ticket_id`, `created_at`);

ALTER TABLE `role_permissions` ADD FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`);

ALTER TABLE `role_permissions` ADD FOREIGN KEY (`permission_id`) REFERENCES `permissions` (`id`);

ALTER TABLE `account_tokens` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `employees` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `customers` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `audit_logs` ADD FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `hotel_settings` ADD FOREIGN KEY (`default_deposit_policy_id`) REFERENCES `deposit_policies` (`id`);

ALTER TABLE `hotel_settings` ADD FOREIGN KEY (`default_cancellation_policy_id`) REFERENCES `cancellation_policies` (`id`);

ALTER TABLE `hotel_settings` ADD FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`);

ALTER TABLE `system_settings` ADD FOREIGN KEY (`updated_by`) REFERENCES `users` (`id`);

ALTER TABLE `room_type_images` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `room_type_amenities` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `room_type_amenities` ADD FOREIGN KEY (`amenity_id`) REFERENCES `amenities` (`id`);

ALTER TABLE `rooms` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `rooms` ADD FOREIGN KEY (`floor_id`) REFERENCES `floors` (`id`);

ALTER TABLE `room_status_history` ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `room_status_history` ADD FOREIGN KEY (`changed_by`) REFERENCES `users` (`id`);

ALTER TABLE `work_sessions` ADD FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`);

ALTER TABLE `maintenance_requests` ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `maintenance_requests` ADD FOREIGN KEY (`reported_by`) REFERENCES `users` (`id`);

ALTER TABLE `maintenance_requests` ADD FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`);

ALTER TABLE `room_blocks` ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `room_blocks` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `room_blocks` ADD FOREIGN KEY (`released_by`) REFERENCES `users` (`id`);

ALTER TABLE `room_blocks` ADD FOREIGN KEY (`maintenance_request_id`, `room_id`) REFERENCES `maintenance_requests` (`id`, `room_id`);

ALTER TABLE `housekeeping_tasks` ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `housekeeping_tasks` ADD FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`);

ALTER TABLE `housekeeping_tasks` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `housekeeping_tasks` ADD FOREIGN KEY (`verified_by`) REFERENCES `users` (`id`);

ALTER TABLE `cancellation_policy_rules` ADD FOREIGN KEY (`cancellation_policy_id`) REFERENCES `cancellation_policies` (`id`);

ALTER TABLE `surcharge_policies` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `rate_rule_room_types` ADD FOREIGN KEY (`rate_rule_id`) REFERENCES `rate_rules` (`id`);

ALTER TABLE `rate_rule_room_types` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `vouchers` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `voucher_room_types` ADD FOREIGN KEY (`voucher_id`) REFERENCES `vouchers` (`id`);

ALTER TABLE `voucher_room_types` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`deposit_policy_id`) REFERENCES `deposit_policies` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`cancellation_policy_id`) REFERENCES `cancellation_policies` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`confirmed_by`) REFERENCES `users` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`rejected_by`) REFERENCES `users` (`id`);

ALTER TABLE `bookings` ADD FOREIGN KEY (`rebooked_from_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_rooms` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_rooms` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `booking_rooms` ADD FOREIGN KEY (`checked_in_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_rooms` ADD FOREIGN KEY (`checked_out_by`) REFERENCES `users` (`id`);

ALTER TABLE `room_type_inventory` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `booking_room_nights` ADD FOREIGN KEY (`booking_room_id`) REFERENCES `booking_rooms` (`id`);

ALTER TABLE `booking_room_nights` ADD FOREIGN KEY (`rate_rule_id`) REFERENCES `rate_rules` (`id`);

ALTER TABLE `booking_room_nights` ADD FOREIGN KEY (`length_discount_rule_id`) REFERENCES `rate_rules` (`id`);

ALTER TABLE `booking_room_nights` ADD FOREIGN KEY (`inventory_room_type_id`, `stay_date`) REFERENCES `room_type_inventory` (`room_type_id`, `stay_date`);

ALTER TABLE `room_allocations` ADD FOREIGN KEY (`booking_room_id`) REFERENCES `booking_rooms` (`id`);

ALTER TABLE `room_allocations` ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `room_allocations` ADD FOREIGN KEY (`assigned_by`) REFERENCES `users` (`id`);

ALTER TABLE `room_allocations` ADD FOREIGN KEY (`ended_by`) REFERENCES `users` (`id`);

ALTER TABLE `room_night_claims` ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `room_night_claims` ADD FOREIGN KEY (`room_allocation_id`, `room_id`) REFERENCES `room_allocations` (`id`, `room_id`);

ALTER TABLE `room_night_claims` ADD FOREIGN KEY (`room_block_id`, `room_id`) REFERENCES `room_blocks` (`id`, `room_id`);

ALTER TABLE `booking_guests` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_guests` ADD FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`);

ALTER TABLE `stay_guests` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `stay_guests` ADD FOREIGN KEY (`booking_guest_id`, `booking_id`) REFERENCES `booking_guests` (`id`, `booking_id`);

ALTER TABLE `stay_guests` ADD FOREIGN KEY (`room_allocation_id`, `booking_room_id`) REFERENCES `room_allocations` (`id`, `booking_room_id`);

ALTER TABLE `booking_status_history` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_status_history` ADD FOREIGN KEY (`changed_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_status_history` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `booking_change_requests` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_change_requests` ADD FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_change_requests` ADD FOREIGN KEY (`handled_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_change_requests` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `booking_cancellations` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_cancellations` ADD FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_cancellations` ADD FOREIGN KEY (`processed_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_cancellations` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `booking_qr_tokens` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `voucher_redemptions` ADD FOREIGN KEY (`voucher_id`) REFERENCES `vouchers` (`id`);

ALTER TABLE `voucher_redemptions` ADD FOREIGN KEY (`booking_id`, `customer_id`) REFERENCES `bookings` (`id`, `customer_id`);

ALTER TABLE `services` ADD FOREIGN KEY (`category_id`) REFERENCES `service_categories` (`id`);

ALTER TABLE `service_usages` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `service_usages` ADD FOREIGN KEY (`service_id`) REFERENCES `services` (`id`);

ALTER TABLE `service_usages` ADD FOREIGN KEY (`recorded_by`) REFERENCES `users` (`id`);

ALTER TABLE `service_usages` ADD FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`);

ALTER TABLE `service_usages` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `service_usages` ADD FOREIGN KEY (`room_allocation_id`, `booking_room_id`) REFERENCES `room_allocations` (`id`, `booking_room_id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`surcharge_policy_id`) REFERENCES `surcharge_policies` (`id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`booking_room_night_id`, `booking_room_id`) REFERENCES `booking_room_nights` (`id`, `booking_room_id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`service_usage_id`, `booking_id`) REFERENCES `service_usages` (`id`, `booking_id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`cancellation_id`, `booking_id`) REFERENCES `booking_cancellations` (`id`, `booking_id`);

ALTER TABLE `booking_charges` ADD FOREIGN KEY (`adjusts_charge_id`, `booking_id`) REFERENCES `booking_charges` (`id`, `booking_id`);

ALTER TABLE `payment_transactions` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `payment_transactions` ADD FOREIGN KEY (`work_session_id`) REFERENCES `work_sessions` (`id`);

ALTER TABLE `payment_transactions` ADD FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `payment_transactions` ADD FOREIGN KEY (`approved_by`) REFERENCES `users` (`id`);

ALTER TABLE `payment_transactions` ADD FOREIGN KEY (`parent_payment_id`, `booking_id`) REFERENCES `payment_transactions` (`id`, `booking_id`);

ALTER TABLE `payment_webhook_events` ADD FOREIGN KEY (`payment_transaction_id`) REFERENCES `payment_transactions` (`id`);

ALTER TABLE `invoices` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `invoices` ADD FOREIGN KEY (`issued_by`) REFERENCES `users` (`id`);

ALTER TABLE `invoices` ADD FOREIGN KEY (`voided_by`) REFERENCES `users` (`id`);

ALTER TABLE `invoices` ADD FOREIGN KEY (`supersedes_invoice_id`, `booking_id`) REFERENCES `invoices` (`id`, `booking_id`);

ALTER TABLE `invoice_lines` ADD FOREIGN KEY (`invoice_id`, `booking_id`) REFERENCES `invoices` (`id`, `booking_id`);

ALTER TABLE `invoice_lines` ADD FOREIGN KEY (`booking_charge_id`, `booking_id`) REFERENCES `booking_charges` (`id`, `booking_id`);

ALTER TABLE `notifications` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `notifications` ADD FOREIGN KEY (`booking_id`) REFERENCES `bookings` (`id`);

ALTER TABLE `notifications` ADD FOREIGN KEY (`outbox_event_id`) REFERENCES `outbox_events` (`id`);

ALTER TABLE `notification_deliveries` ADD FOREIGN KEY (`notification_id`) REFERENCES `notifications` (`id`);

ALTER TABLE `notification_deliveries` ADD FOREIGN KEY (`outbox_event_id`) REFERENCES `outbox_events` (`id`);

ALTER TABLE `favorites` ADD FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`);

ALTER TABLE `favorites` ADD FOREIGN KEY (`room_type_id`) REFERENCES `room_types` (`id`);

ALTER TABLE `reviews` ADD FOREIGN KEY (`moderated_by`) REFERENCES `users` (`id`);

ALTER TABLE `reviews` ADD FOREIGN KEY (`replied_by`) REFERENCES `users` (`id`);

ALTER TABLE `reviews` ADD FOREIGN KEY (`booking_room_id`, `booking_id`) REFERENCES `booking_rooms` (`id`, `booking_id`);

ALTER TABLE `reviews` ADD FOREIGN KEY (`booking_id`, `customer_id`) REFERENCES `bookings` (`id`, `customer_id`);

ALTER TABLE `support_tickets` ADD FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`);

ALTER TABLE `support_tickets` ADD FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`);

ALTER TABLE `support_tickets` ADD FOREIGN KEY (`booking_id`, `customer_id`) REFERENCES `bookings` (`id`, `customer_id`);

ALTER TABLE `support_messages` ADD FOREIGN KEY (`ticket_id`) REFERENCES `support_tickets` (`id`);

ALTER TABLE `support_messages` ADD FOREIGN KEY (`sender_user_id`) REFERENCES `users` (`id`);
