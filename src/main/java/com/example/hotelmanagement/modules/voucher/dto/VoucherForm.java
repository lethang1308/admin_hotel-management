package com.example.hotelmanagement.modules.voucher.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
public class VoucherForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 50, message = "{cms.validation.size}")
    @Pattern(regexp = "[A-Za-z0-9_-]+", message = "{cms.validation.format}")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(max = 150, message = "{cms.validation.size}")
    private String name;

    @Size(max = 20000, message = "{cms.validation.size}")
    private String description;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.voucher.entity.VoucherDiscountType discountType =
            com.example.hotelmanagement.modules.voucher.entity.VoucherDiscountType.PERCENT;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal discountValue = BigDecimal.TEN;

    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal maxDiscountAmount;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(value = "0", message = "{cms.validation.range}")
    @Digits(integer = 13, fraction = 2, message = "{cms.validation.range}")
    private BigDecimal minRoomSubtotal = BigDecimal.ZERO;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startsAt;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime expiresAt;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate stayFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate stayUntil;

    @Min(value = 1, message = "{cms.validation.range}")
    private Integer maxUses;

    @NotNull(message = "{cms.validation.required}")
    @Min(value = 1, message = "{cms.validation.range}")
    @Max(value = 2147483647, message = "{cms.validation.range}")
    private Integer maxUsesPerCustomer = 1;

    private boolean combinableWithLengthDiscount = true;

    @Size(max = 100, message = "{cms.validation.size}")
    private Set<@Positive(message = "{cms.validation.range}") Long> roomTypeIds =
            new LinkedHashSet<>();

    private boolean active = true;
}
