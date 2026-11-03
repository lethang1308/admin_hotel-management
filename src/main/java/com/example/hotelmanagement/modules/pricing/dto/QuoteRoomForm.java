package com.example.hotelmanagement.modules.pricing.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class QuoteRoomForm {
    @NotNull(message = "{cms.validation.required}")
    @Positive(message = "{cms.validation.range}")
    private Long roomTypeId;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkIn;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate checkOut;

    @NotNull(message = "{cms.validation.required}")
    @Min(value = 1, message = "{cms.validation.range}")
    @Max(value = 20, message = "{cms.validation.range}")
    private Integer quantity = 1;
}
