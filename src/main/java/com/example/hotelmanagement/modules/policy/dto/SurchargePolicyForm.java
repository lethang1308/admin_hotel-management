package com.example.hotelmanagement.modules.policy.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class SurchargePolicyForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 30, message = "{cms.validation.size}")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "{cms.validation.format}")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 150, message = "{cms.validation.size}")
    private String name;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.policy.entity.SurchargeKind kind =
            com.example.hotelmanagement.modules.policy.entity.SurchargeKind.LATE_CHECKOUT;

    @Positive(message = "{cms.validation.range}")
    private Long roomTypeId;

    @Min(value = 0, message = "{cms.validation.range}")
    @Max(value = 100000, message = "{cms.validation.range}")
    private Integer fromMinutes;

    @Min(value = 1, message = "{cms.validation.range}")
    @Max(value = 100000, message = "{cms.validation.range}")
    private Integer toMinutes;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.policy.entity.SurchargeAmountType amountType =
            com.example.hotelmanagement.modules.policy.entity.SurchargeAmountType.FIXED;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal amountValue = BigDecimal.ZERO;

    @NotNull(message = "{cms.validation.required}")
    @Min(value = -100000, message = "{cms.validation.range}")
    @Max(value = 100000, message = "{cms.validation.range}")
    private Integer priority = 0;

    private boolean active = true;
}
