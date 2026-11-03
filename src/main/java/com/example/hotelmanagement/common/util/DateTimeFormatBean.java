package com.example.hotelmanagement.common.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component("dateFmt")
public class DateTimeFormatBean {

    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public String utc(Instant instant) {
        return instant != null ? UTC_FORMATTER.format(instant) : "-";
    }

    public String local(Instant instant) {
        return instant == null
                ? "-"
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .format(instant);
    }
}
