package com.example.hotelmanagement.modules.floor.dto;

import jakarta.validation.constraints.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class FloorForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 30)
    @Pattern(message = "{cms.validation.format}", regexp = "[A-Za-z0-9_-]+")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 100)
    private String name;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 100)
    private String areaName;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = -10)
    @Max(message = "{cms.validation.range}", value = 200)
    private Integer floorNumber = 1;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = 0)
    @Max(message = "{cms.validation.range}", value = 10000)
    private Integer sortOrder = 0;

    private boolean active = true;
}
