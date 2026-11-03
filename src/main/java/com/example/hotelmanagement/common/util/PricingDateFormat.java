package com.example.hotelmanagement.common.util;

import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;

@Component("pricingDates")
public class PricingDateFormat {
    public String local(Instant instant, String zone) {
        return instant == null
                ? "—"
                : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                        .withZone(ZoneId.of(zone))
                        .format(instant);
    }
}
