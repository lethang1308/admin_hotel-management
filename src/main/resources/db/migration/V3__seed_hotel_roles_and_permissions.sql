-- Reference data only: no demo accounts, rooms, prices, bookings or monetary policies.
-- Existing ADMIN/USER roles and their account assignments remain unchanged.
-- These permission rows prepare the hotel modules; current endpoints still use
-- their existing role-based Spring Security checks until those modules are built.

INSERT INTO roles (code, name, description, created_at, updated_at)
SELECT 'RECEPTIONIST', 'Receptionist', 'Hotel front desk and stay operations',
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'RECEPTIONIST');

INSERT INTO permissions (code, name, description) VALUES
('account.manage', 'Manage employee accounts', 'Create, update and lock employee accounts'),
('role.manage', 'Manage roles and permissions', 'Maintain API permission assignments'),
('customer.read', 'Read customer profiles', 'Look up customer and stay history within permitted scope'),
('customer.update', 'Update customer profiles', 'Update customer information within permitted scope'),
('profile.manage.own', 'Manage own profile', 'Read and update the authenticated account profile'),
('hotel.manage', 'Manage hotel information', 'Maintain hotel contact and configuration'),
('room.read', 'Read rooms and availability', 'Search rooms, amenities and availability'),
('room.manage', 'Manage room catalogue', 'Maintain rooms, types, floors, images and amenities'),
('room.status.update', 'Update operational room status', 'Maintain current room readiness'),
('room.block.manage', 'Manage room blocks', 'Block and release rooms for maintenance'),
('housekeeping.manage', 'Manage housekeeping', 'Create and verify cleaning tasks'),
('maintenance.manage', 'Manage maintenance requests', 'Report and resolve room faults'),
('pricing.manage', 'Manage room pricing', 'Maintain seasonal, weekend and length-of-stay rules'),
('policy.manage', 'Manage booking policies', 'Maintain deposit, cancellation and surcharge policies'),
('voucher.manage', 'Manage vouchers', 'Maintain promotion conditions and limits'),
('booking.create', 'Create bookings', 'Create online, phone or walk-in bookings within permitted scope'),
('booking.read.any', 'Read operational bookings', 'Search all bookings for hotel operations'),
('booking.read.own', 'Read own bookings', 'Read only bookings belonging to the authenticated customer'),
('booking.confirm', 'Confirm or reject bookings', 'Review pending booking requests'),
('booking.update.any', 'Adjust bookings', 'Change dates, extend, shorten and allocate rooms'),
('booking.change.request.own', 'Request own booking changes', 'Request changes for owned bookings'),
('booking.cancel.any', 'Cancel bookings', 'Cancel bookings subject to policy and permissions'),
('booking.cancel.own', 'Cancel own bookings', 'Cancel owned bookings within policy deadlines'),
('booking.checkin', 'Check guests in', 'Verify guests and begin stays'),
('booking.checkout', 'Check guests out', 'Finalize stays and room turnover'),
('booking.no_show', 'Process no-show', 'Mark arrivals as no-show subject to policy'),
('booking.qr.scan', 'Scan booking QR codes', 'Look up bookings for authenticated front desk users'),
('service.read', 'Read service catalogue', 'Read offered hotel services'),
('service.manage', 'Manage service catalogue', 'Maintain services and prices'),
('service.usage.manage', 'Record service usage', 'Record or void stay service consumption'),
('payment.collect', 'Collect payments', 'Record deposits and payments at the hotel'),
('payment.pay.own', 'Pay own bookings', 'Initiate payment for owned bookings'),
('payment.refund', 'Approve and process refunds', 'Refund successful collections within permitted limits'),
('invoice.manage', 'Manage invoices', 'Issue, void and replace internal invoices'),
('invoice.read.own', 'Read own invoices', 'View and download invoices for owned bookings'),
('report.revenue', 'Read financial reports', 'Read revenue, ADR, RevPAR and payment reports'),
('report.shift', 'Read shift summary', 'Read operational shift and cash summaries'),
('audit.read', 'Read audit log', 'Inspect authorized operational activity logs'),
('notification.manage', 'Manage own notifications', 'Read and mark owned notifications'),
('favorite.manage.own', 'Manage favorites', 'Maintain owned favorite room types'),
('review.create.own', 'Review completed stays', 'Review eligible owned room stays'),
('review.moderate', 'Moderate reviews', 'Moderate and reply to guest reviews'),
('support.manage', 'Handle support tickets', 'Read and respond to authorized customer requests'),
('support.manage.own', 'Manage own support tickets', 'Create and follow owned support tickets'),
('system.configure', 'Configure system', 'Maintain non-secret application settings');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN';

-- Refund approval, price changes and financial reporting are not assigned to
-- receptionists by default. Scope and ownership still require service checks.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'RECEPTIONIST' AND p.code IN (
    'profile.manage.own', 'customer.read', 'customer.update', 'room.read',
    'room.status.update', 'housekeeping.manage', 'maintenance.manage',
    'booking.create', 'booking.read.any', 'booking.confirm', 'booking.update.any',
    'booking.cancel.any', 'booking.checkin', 'booking.checkout', 'booking.no_show',
    'booking.qr.scan', 'service.read', 'service.usage.manage', 'payment.collect',
    'invoice.manage', 'report.shift', 'notification.manage', 'support.manage'
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'USER' AND p.code IN (
    'profile.manage.own', 'room.read', 'booking.create', 'booking.read.own',
    'booking.change.request.own', 'booking.cancel.own', 'service.read',
    'payment.pay.own', 'invoice.read.own', 'notification.manage',
    'favorite.manage.own', 'review.create.own', 'support.manage.own'
);
