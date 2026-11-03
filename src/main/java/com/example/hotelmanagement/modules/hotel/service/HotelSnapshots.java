package com.example.hotelmanagement.modules.hotel.service;

import java.util.*;

public final class HotelSnapshots {
    private HotelSnapshots() {}

    public static Map<String, Object> of(Record record) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (var c : record.getClass().getRecordComponents())
                out.put(c.getName(), value(c.getAccessor().invoke(record)));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create hotel audit snapshot", e);
        }
        return out;
    }

    private static Object value(Object value) {
        if (value instanceof Record r) return of(r);
        if (value instanceof Collection<?> values)
            return values.stream().map(HotelSnapshots::value).toList();
        return value;
    }
}
