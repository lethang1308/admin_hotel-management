package com.example.hotelmanagement.modules.pricing.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class BasePriceForm {
    @NotNull(message = "{cms.validation.required}")
    @Positive(message = "{cms.validation.range}")
    private Long roomTypeId;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal basePrice;
}
