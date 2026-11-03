package com.example.hotelmanagement.modules.policy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class CancellationPolicyForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 30, message = "{cms.validation.size}")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "{cms.validation.format}")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 150, message = "{cms.validation.size}")
    private String name;

    @Size(max = 20000, message = "{cms.validation.size}")
    private String description;

    private boolean customerCanCancel = true;

    @NotNull(message = "{cms.validation.required}")
    @Min(value = 0, message = "{cms.validation.range}")
    @Max(value = 100000, message = "{cms.validation.range}")
    private Integer customerCancelDeadlineHours = 0;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.policy.entity.FeeType noShowFeeType =
            com.example.hotelmanagement.modules.policy.entity.FeeType.PERCENT;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal noShowFeeValue = new BigDecimal("100");

    @Valid
    @NotNull(message = "{cms.validation.required}")
    @Size(min = 1, max = 30, message = "{cms.validation.size}")
    private List<CancellationTierForm> rules = new ArrayList<>(List.of(new CancellationTierForm()));

    private boolean active = true;
}
