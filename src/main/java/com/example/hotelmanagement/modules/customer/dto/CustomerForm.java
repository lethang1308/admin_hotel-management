package com.example.hotelmanagement.modules.customer.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class CustomerForm {
    @NotBlank(message="{cms.validation.required}")
    @Size(max = 150, message="{cms.validation.size}")
    private String fullName;

    @Email(message="{cms.validation.email}")
    @Size(max = 150, message="{cms.validation.size}")
    private String email;

    @Size(max = 20, message="{cms.validation.size}")
    @Pattern(regexp="[+0-9 .()-]*", message="{cms.validation.format}")
    private String phone;

    @Past(message="{cms.validation.past}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateOfBirth;

    @Pattern(regexp="[A-Za-z]{2}", message="{cms.validation.format}")
    private String nationalityCode = "VN";

    @Size(max = 500, message="{cms.validation.size}")
    private String address;

    @Size(max = 5000, message="{cms.validation.size}")
    private String notes;

    @Pattern(regexp="|CCCD|PASSPORT", message="{cms.validation.format}")
    private String documentType = "";

    @Size(max = 50, message="{cms.validation.size}")
    @Pattern(regexp="[A-Za-z0-9 -]*", message="{cms.validation.format}")
    private String documentNumber;

    private boolean clearDocument;

    @Size(max = 1000, message="{cms.validation.size}")
    private String reason;
}
