package com.example.hotelmanagement.modules.room.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
@NoArgsConstructor
public class RoomStatusChangeForm {
    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.room.entity.RoomStatus status;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 500)
    private String reason;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate maintenanceUntil;

    @Min(message = "{cms.validation.range}", value = 0)
    private Integer version;
}
