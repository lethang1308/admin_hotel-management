package com.example.hotelmanagement.modules.policy.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class CancellationTierForm {
    @NotNull(message = "{cms.validation.required}")
    @Min(value = 0, message = "{cms.validation.range}")
    @Max(value = 100000, message = "{cms.validation.range}")
    private Integer minHoursBeforeCheckin = 0;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.policy.entity.FeeType feeType =
            com.example.hotelmanagement.modules.policy.entity.FeeType.NONE;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal feeValue = BigDecimal.ZERO;
}
