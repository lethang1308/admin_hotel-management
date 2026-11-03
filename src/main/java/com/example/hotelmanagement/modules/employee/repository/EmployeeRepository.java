package com.example.hotelmanagement.modules.employee.repository;

import com.example.hotelmanagement.modules.employee.entity.Employee;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;

public interface EmployeeRepository
        extends JpaRepository<Employee, Long>, JpaSpecificationExecutor<Employee> {
    Optional<Employee> findByUser_Id(Long userId);

    boolean existsByEmployeeCode(String code);

    boolean existsByEmployeeCodeAndIdNot(String code, Long id);
}
