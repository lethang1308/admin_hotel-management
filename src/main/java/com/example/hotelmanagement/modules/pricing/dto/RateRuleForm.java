package com.example.hotelmanagement.modules.pricing.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class RateRuleForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 40, message = "{cms.validation.size}")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "{cms.validation.format}")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 150, message = "{cms.validation.size}")
    private String name;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.pricing.entity.RateRuleKind kind =
            com.example.hotelmanagement.modules.pricing.entity.RateRuleKind.NIGHT_OVERRIDE;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    @NotNull(message = "{cms.validation.required}")
    @Size(min = 1, max = 7, message = "{cms.validation.size}")
    private Set<
                    @Min(value = 1, message = "{cms.validation.range}")
                    @Max(value = 7, message = "{cms.validation.range}") Integer>
            weekdays = new LinkedHashSet<>(List.of(1, 2, 3, 4, 5, 6, 7));

    @NotNull(message = "{cms.validation.required}")
    @Min(value = 1, message = "{cms.validation.range}")
    @Max(value = 366, message = "{cms.validation.range}")
    private Integer minNights = 1;

    @Min(value = 1, message = "{cms.validation.range}")
    @Max(value = 366, message = "{cms.validation.range}")
    private Integer maxNights;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.pricing.entity.RateValueType valueType =
            com.example.hotelmanagement.modules.pricing.entity.RateValueType.FIXED;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal value = BigDecimal.ZERO;

    @NotNull(message = "{cms.validation.required}")
    @Min(value = -100000, message = "{cms.validation.range}")
    @Max(value = 100000, message = "{cms.validation.range}")
    private Integer priority = 0;

    @NotNull(message = "{cms.validation.required}")
    @Size(min = 1, max = 100, message = "{cms.validation.size}")
    private Set<@Positive(message = "{cms.validation.range}") Long> roomTypeIds =
            new LinkedHashSet<>();

    private boolean active = true;
}
