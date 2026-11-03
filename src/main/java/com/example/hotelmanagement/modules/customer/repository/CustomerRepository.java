package com.example.hotelmanagement.modules.customer.repository;

import com.example.hotelmanagement.modules.customer.entity.Customer;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;

public interface CustomerRepository
        extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {
    Optional<Customer> findByUser_Id(Long userId);
}
