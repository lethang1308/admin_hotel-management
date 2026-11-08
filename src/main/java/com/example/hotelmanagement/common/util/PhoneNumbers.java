package com.example.hotelmanagement.common.util;

public final class PhoneNumbers {
    private PhoneNumbers() {}

    public static String normalize(String value) {
        if (value == null) return null;
        String phone = value.strip().replaceAll("[\\s().-]", "");
        if (phone.startsWith("+84")) phone = "0" + phone.substring(3);
        return phone;
    }

    public static boolean vietnamese(String value) {
        return value != null && normalize(value).matches("0[35789][0-9]{8}");
    }
}
