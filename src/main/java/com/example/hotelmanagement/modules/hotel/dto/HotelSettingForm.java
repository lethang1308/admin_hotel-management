package com.example.hotelmanagement.modules.hotel.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
@NoArgsConstructor
public class HotelSettingForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 200)
    private String name;

    @Size(message = "{cms.validation.size}", max = 20000)
    private String description;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 500)
    private String address;

    @Size(message = "{cms.validation.size}", max = 30)
    private String hotline;

    @Email(message = "{cms.validation.email}")
    @Size(message = "{cms.validation.size}", max = 150)
    private String email;

    @Size(message = "{cms.validation.size}", max = 500)
    private String logoUrl;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private java.time.LocalTime standardCheckIn;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private java.time.LocalTime standardCheckOut;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = 1)
    @Max(message = "{cms.validation.range}", value = 1440)
    private Integer bookingHoldMinutes = 15;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(message = "{cms.validation.range}", value = "0")
    @DecimalMax(message = "{cms.validation.range}", value = "100")
    @Digits(integer = 3, fraction = 2, message = "{cms.validation.range}")
    private java.math.BigDecimal defaultTaxPercent = java.math.BigDecimal.ZERO;

    @NotNull(message = "{cms.validation.required}")
    private Long defaultDepositPolicyId;

    @NotNull(message = "{cms.validation.required}")
    private Long defaultCancellationPolicyId;
}
