package com.example.hotelmanagement.modules.employee.entity;

import com.example.hotelmanagement.common.audit.BaseAuditableEntity;
import com.example.hotelmanagement.modules.user.entity.User;

import jakarta.persistence.*;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "employees")
public class Employee extends BaseAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "employee_code", nullable = false, unique = true, length = 30)
    private String employeeCode;

    @Column(length = 100)
    private String position;

    @Column(name = "hired_on")
    private LocalDate hiredOn;

    @Column(name = "ended_on")
    private LocalDate endedOn;
}
