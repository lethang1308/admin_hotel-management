package com.example.hotelmanagement.modules.customer.entity;

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
@Table(name = "customers")
public class Customer extends BaseAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(name = "customer_code", nullable = false, unique = true, length = 30)
    private String customerCode;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(length = 150)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "nationality_code", length = 2, columnDefinition = "char(2)")
    private String nationalityCode;

    @Column(length = 500)
    private String address;

    @Column(length = 20)
    private String gender;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "document_type", length = 30)
    private String documentType;

    @Column(name = "document_number_encrypted", columnDefinition = "text")
    private String documentNumberEncrypted;

    @Column(name = "document_number_hash", length = 64, columnDefinition = "char(64)")
    private String documentNumberHash;
}
