package com.example.hotelmanagement.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CmsTestData {
    private CmsTestData() {}

    public static void clear(JdbcTemplate jdbc) {
        jdbc.update("UPDATE hotel_settings SET updated_by=NULL");
        for (String table :
                new String[] {
                    "audit_logs",
                    "room_night_claims",
                    "booking_room_nights",
                    "room_allocations",
                    "booking_guests",
                    "booking_rooms",
                    "bookings",
                    "room_blocks",
                    "room_status_history",
                    "rooms",
                    "floors",
                    "room_type_images",
                    "room_type_amenities",
                    "room_type_inventory",
                    "account_tokens",
                    "employees",
                    "customers",
                    "refresh_tokens",
                    "user_roles",
                    "users"
                }) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM room_types WHERE code NOT IN ('STD','SUP','DLX','SUI','VIP')");
        jdbc.update(
                "DELETE FROM amenities WHERE code NOT IN"
                        + " ('WIFI','TV','AC','BATHTUB','BALCONY','MINIBAR','SAFE')");
    }
}
