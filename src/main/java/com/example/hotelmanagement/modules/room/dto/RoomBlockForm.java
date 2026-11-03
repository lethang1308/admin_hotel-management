package com.example.hotelmanagement.modules.room.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
@NoArgsConstructor
public class RoomBlockForm {
    @NotNull(message = "{cms.validation.required}")
    private Long roomId;

    @NotNull(message = "{cms.validation.required}")
    private com.example.hotelmanagement.modules.room.entity.RoomBlockType blockType =
            com.example.hotelmanagement.modules.room.entity.RoomBlockType.MAINTENANCE;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate fromDate;

    @NotNull(message = "{cms.validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private java.time.LocalDate toDate;

    @NotBlank(message = "{cms.validation.required}")
    @Size(message = "{cms.validation.size}", max = 500)
    private String reason;
}
