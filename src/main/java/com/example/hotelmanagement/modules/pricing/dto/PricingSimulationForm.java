package com.example.hotelmanagement.modules.pricing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class PricingSimulationForm {
    @Valid
    @NotNull(message = "{cms.validation.required}")
    @Size(min = 1, max = 20, message = "{cms.validation.size}")
    private List<QuoteRoomForm> rooms = new ArrayList<>(List.of(new QuoteRoomForm()));

    @Size(max = 50, message = "{cms.validation.size}")
    private String voucherCode;

    @Positive(message = "{cms.validation.range}")
    private Long customerId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime evaluationTime;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime cancellationTime;
}
