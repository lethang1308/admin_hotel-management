package com.example.hotelmanagement.modules.employee.dto;

import jakarta.validation.constraints.*;

import lombok.*;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class EmployeeForm {
    @Size(max = 30, message="{cms.validation.size}")
    @Pattern(regexp="[A-Za-z0-9_-]*", message="{cms.validation.format}")
    private String employeeCode;

    @NotBlank(message="{cms.validation.required}")
    @Size(max = 120, message="{cms.validation.size}")
    private String fullName;

    @NotBlank(message="{cms.validation.required}")
    @Email(message="{cms.validation.email}")
    @Size(max = 150, message="{cms.validation.size}")
    private String email;

    @Size(max = 20, message="{cms.validation.size}")
    @Pattern(regexp="[+0-9 .()-]*", message="{cms.validation.format}")
    private String phone;

    @Size(max = 100, message="{cms.validation.size}")
    private String position;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate hiredOn;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endedOn;

    @Size(max = 72, message="{cms.validation.size}")
    private String password;

    @Size(max = 1000, message="{cms.validation.size}")
    private String reason;
}
