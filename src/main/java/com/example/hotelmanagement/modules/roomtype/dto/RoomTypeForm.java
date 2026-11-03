package com.example.hotelmanagement.modules.roomtype.dto;

import jakarta.validation.constraints.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class RoomTypeForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 30)
    @Pattern(message = "{cms.validation.format}", regexp = "[A-Za-z0-9_-]+")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 100)
    private String name;

    @Size(message = "{cms.validation.size}", max = 20000)
    private String description;

    @NotNull(message = "{cms.validation.required}")
    @DecimalMin(message = "{cms.validation.range}", value = "0")
    @Digits(message = "{cms.validation.range}", integer = 13, fraction = 2)
    private java.math.BigDecimal basePrice = java.math.BigDecimal.ZERO;

    @DecimalMin(message = "{cms.validation.range}", value = "0.01")
    @Digits(message = "{cms.validation.range}", integer = 6, fraction = 2)
    private java.math.BigDecimal areaSqm;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = 1)
    @Max(message = "{cms.validation.range}", value = 100)
    private Integer maxAdults = 2;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = 0)
    @Max(message = "{cms.validation.range}", value = 100)
    private Integer maxChildren = 0;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = 1)
    @Max(message = "{cms.validation.range}", value = 200)
    private Integer maxOccupancy = 2;

    @Size(message = "{cms.validation.size}", max = 200)
    private String bedDescription;

    private boolean featured;
    private boolean active = true;
    private java.util.Set<Long> amenityIds = new java.util.LinkedHashSet<>();
}
