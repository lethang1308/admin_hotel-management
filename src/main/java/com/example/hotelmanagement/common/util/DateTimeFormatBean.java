package com.example.hotelmanagement.common.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component("dateFmt")
public class DateTimeFormatBean {

    private static final DateTimeFormatter UTC_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public String utc(Instant instant) {
        return instant != null ? UTC_FORMATTER.format(instant) : "-";
    }
}
