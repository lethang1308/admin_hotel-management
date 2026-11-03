package com.example.hotelmanagement.modules.policy.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class DepositPolicyForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 30, message = "{cms.validation.size}")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "{cms.validation.format}")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 150, message = "{cms.validation.size}")
    private String name;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.policy.entity.DepositAmountType amountType =
            com.example.hotelmanagement.modules.policy.entity.DepositAmountType.NONE;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal amountValue = BigDecimal.ZERO;

    @NotNull(message = "{cms.validation.required}")
    @Min(value = 1, message = "{cms.validation.range}")
    @Max(value = 525600, message = "{cms.validation.range}")
    private Integer paymentDueMinutes = 15;

    private boolean active = true;
}
