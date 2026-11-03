-- Reference setup for the CMS. Existing hotel settings/catalogues are never overwritten.
-- Default policies impose no deposit/cancellation fee; configure real business policies before opening bookings.
INSERT INTO deposit_policies (code, version, name, amount_type, amount_value, payment_due_minutes, is_active)
SELECT 'CMS_NO_DEPOSIT', 1, 'No deposit', 'NONE', 0, 15, true
WHERE NOT EXISTS (SELECT 1 FROM deposit_policies WHERE code='CMS_NO_DEPOSIT' AND version=1);

INSERT INTO cancellation_policies (code, version, name, customer_can_cancel, customer_cancel_deadline_hours,
                                   no_show_fee_type, no_show_fee_value, is_active)
SELECT 'CMS_FLEXIBLE', 1, 'Flexible cancellation', true, 0, 'NONE', 0, true
WHERE NOT EXISTS (SELECT 1 FROM cancellation_policies WHERE code='CMS_FLEXIBLE' AND version=1);

INSERT INTO cancellation_policy_rules (cancellation_policy_id, min_hours_before_checkin, fee_type, fee_value)
SELECT p.id, 0, 'NONE', 0 FROM cancellation_policies p
WHERE p.code='CMS_FLEXIBLE' AND p.version=1
  AND NOT EXISTS (SELECT 1 FROM cancellation_policy_rules r WHERE r.cancellation_policy_id=p.id AND r.min_hours_before_checkin=0);

INSERT INTO hotel_settings (id, name, address, default_deposit_policy_id, default_cancellation_policy_id)
SELECT 1, 'Hotel', 'Please update the hotel address', d.id, c.id
FROM deposit_policies d CROSS JOIN cancellation_policies c
WHERE d.code='CMS_NO_DEPOSIT' AND d.version=1 AND c.code='CMS_FLEXIBLE' AND c.version=1
  AND NOT EXISTS (SELECT 1 FROM hotel_settings WHERE id=1);

-- Starter categories only. Zero prices explicitly require configuration, not invented room rates.
INSERT INTO room_types (code, name, base_price, max_adults, max_children, max_occupancy)
SELECT starter.code, starter.name, 0, 2, 0, 2
FROM (
    SELECT 'STD' AS code, 'Standard' AS name UNION ALL
    SELECT 'SUP', 'Superior' UNION ALL
    SELECT 'DLX', 'Deluxe' UNION ALL
    SELECT 'SUI', 'Suite' UNION ALL
    SELECT 'VIP', 'VIP'
) starter
WHERE NOT EXISTS (SELECT 1 FROM room_types);

INSERT INTO amenities (code, name, icon, is_active)
SELECT starter.code, starter.name, starter.icon, true
FROM (
    SELECT 'WIFI' AS code, 'Wi-Fi' AS name, 'bi-wifi' AS icon UNION ALL
    SELECT 'TV', 'TV', 'bi-tv' UNION ALL
    SELECT 'AC', 'Air conditioning', 'bi-snow' UNION ALL
    SELECT 'BATHTUB', 'Bathtub', 'bi-water' UNION ALL
    SELECT 'BALCONY', 'Balcony', 'bi-sun' UNION ALL
    SELECT 'MINIBAR', 'Minibar', 'bi-cup-straw' UNION ALL
    SELECT 'SAFE', 'Safe', 'bi-safe'
) starter
WHERE NOT EXISTS (SELECT 1 FROM amenities);
