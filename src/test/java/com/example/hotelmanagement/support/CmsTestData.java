package com.example.hotelmanagement.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class CmsTestData {
    private CmsTestData() {}

    public static void clear(JdbcTemplate jdbc) {
        for (String table :
                new String[] {
                    "audit_logs",
                    "account_tokens",
                    "employees",
                    "customers",
                    "refresh_tokens",
                    "user_roles",
                    "users"
                }) jdbc.update("DELETE FROM " + table);
    }
}
