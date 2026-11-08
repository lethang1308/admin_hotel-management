package com.example.hotelmanagement.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CmsTestData {
    private CmsTestData() {}

    public static void clear(JdbcTemplate jdbc) {
        jdbc.update(
                "UPDATE hotel_settings SET updated_by=NULL, default_deposit_policy_id=(SELECT id"
                    + " FROM deposit_policies WHERE code='CMS_NO_DEPOSIT' AND version=1),"
                    + " default_cancellation_policy_id=(SELECT id FROM cancellation_policies WHERE"
                    + " code='CMS_FLEXIBLE' AND version=1), default_tax_percent=0");
        jdbc.update("DELETE FROM notification_deliveries");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM guest_messages");
        jdbc.update("DELETE FROM booking_room_preferences");
        jdbc.update("DELETE FROM payment_webhook_events");
        jdbc.update("DELETE FROM refund_requests");
        jdbc.update(
                "UPDATE hotel_settings SET"
                    + " bank_bin=NULL,bank_account_number=NULL,bank_account_name=NULL,bank_revision=0");
        jdbc.update("DELETE FROM invoice_lines");
        for (String table : new String[] {"invoices", "payment_transactions", "booking_charges"}) {
            for (Long id :
                    jdbc.queryForList("SELECT id FROM " + table + " ORDER BY id DESC", Long.class))
                jdbc.update("DELETE FROM " + table + " WHERE id=?", id);
        }
        for (String table :
                new String[] {
                    "audit_logs",
                    "voucher_redemptions",
                    "voucher_room_types",
                    "vouchers",
                    "work_sessions",
                    "service_usages",
                    "services",
                    "service_categories",
                    "booking_cancellations",
                    "booking_change_requests",
                    "booking_status_history",
                    "stay_guests",
                    "outbox_events",
                    "housekeeping_tasks",
                    "room_night_claims",
                    "booking_room_nights",
                    "room_allocations",
                    "booking_qr_tokens",
                    "booking_guests",
                    "booking_rooms",
                    "bookings",
                    "room_blocks",
                    "room_status_history",
                    "maintenance_requests",
                    "rooms",
                    "floors",
                    "room_type_images",
                    "room_type_amenities",
                    "room_type_inventory",
                    "rate_rule_room_types",
                    "rate_rules",
                    "surcharge_policies",
                    "account_tokens",
                    "employees",
                    "customers",
                    "refresh_tokens",
                    "user_roles",
                    "users"
                }) jdbc.update("DELETE FROM " + table);
        jdbc.update(
                "DELETE FROM cancellation_policy_rules WHERE cancellation_policy_id NOT IN (SELECT"
                    + " id FROM cancellation_policies WHERE code='CMS_FLEXIBLE' AND version=1)");
        jdbc.update("DELETE FROM cancellation_policies WHERE code<>'CMS_FLEXIBLE' OR version<>1");
        jdbc.update("DELETE FROM deposit_policies WHERE code<>'CMS_NO_DEPOSIT' OR version<>1");
        jdbc.update(
                "UPDATE cancellation_policies SET is_active=true WHERE code='CMS_FLEXIBLE' AND"
                        + " version=1");
        jdbc.update(
                "UPDATE deposit_policies SET is_active=true WHERE code='CMS_NO_DEPOSIT' AND"
                        + " version=1");
        jdbc.update("DELETE FROM room_types WHERE code NOT IN ('STD','SUP','DLX','SUI','VIP')");
        jdbc.update(
                "DELETE FROM amenities WHERE code NOT IN"
                        + " ('WIFI','TV','AC','BATHTUB','BALCONY','MINIBAR','SAFE')");
    }
}
