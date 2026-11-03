package com.example.hotelmanagement.modules.roomtype.dto;

import jakarta.validation.constraints.*;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class RoomTypeImageForm {
    @Size(message = "{cms.validation.size}", max = 500)
    private String imageUrl;

    @Size(message = "{cms.validation.size}", max = 200)
    private String altText;

    @NotNull(message = "{cms.validation.required}")
    @Min(message = "{cms.validation.range}", value = 0)
    @Max(message = "{cms.validation.range}", value = 10000)
    private Integer sortOrder = 0;
}
