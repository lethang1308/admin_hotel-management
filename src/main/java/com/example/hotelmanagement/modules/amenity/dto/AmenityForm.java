package com.example.hotelmanagement.modules.amenity.dto;

import jakarta.validation.constraints.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class AmenityForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 50)
    @Pattern(message = "{cms.validation.format}", regexp = "[A-Za-z0-9_-]+")
    private String code;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 100)
    private String name;

    @NotBlank(message = "{cms.validation.required}")
    @Pattern(message = "{cms.validation.format}", regexp = "bi-[a-z0-9-]{1,60}")
    private String icon = "bi-check-circle";

    private boolean active = true;
}
