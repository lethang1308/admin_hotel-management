package com.example.hotelmanagement.modules.room.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
@NoArgsConstructor
public class RoomForm {
    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 20)
    @Pattern(message = "{cms.validation.format}", regexp = "[A-Za-z0-9_-]+")
    private String roomNumber;

    @NotNull(message = "{cms.validation.required}")
    private Long roomTypeId;

    @NotNull(message = "{cms.validation.required}")
    private Long floorId;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate sellFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate sellUntil;

    @Size(message = "{cms.validation.size}", max = 5000)
    private String notes;

    @Min(message = "{cms.validation.range}", value = 0)
    private Integer version;
}
