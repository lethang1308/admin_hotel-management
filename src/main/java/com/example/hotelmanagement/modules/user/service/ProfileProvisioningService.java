package com.example.hotelmanagement.modules.user.service;

import com.example.hotelmanagement.modules.customer.entity.Customer;
import com.example.hotelmanagement.modules.customer.repository.CustomerRepository;
import com.example.hotelmanagement.modules.employee.entity.Employee;
import com.example.hotelmanagement.modules.employee.repository.EmployeeRepository;
import com.example.hotelmanagement.modules.user.entity.User;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileProvisioningService {
    private final EmployeeRepository employees;
    private final CustomerRepository customers;

    public void provision(User user) {
        if ((user.hasRole("ADMIN") || user.hasRole("RECEPTIONIST"))
                && employees.findByUser_Id(user.getId()).isEmpty()) {
            Employee e =
                    employees.saveAndFlush(
                            Employee.builder().user(user).employeeCode(temp()).build());
            e.setEmployeeCode("NV" + String.format("%06d", e.getId()));
        }
        if (user.hasRole("USER") && customers.findByUser_Id(user.getId()).isEmpty()) {
            Customer c =
                    customers.saveAndFlush(
                            Customer.builder()
                                    .user(user)
                                    .customerCode(temp())
                                    .fullName(user.getFullName())
                                    .email(user.getEmail())
                                    .phone(user.getPhone())
                                    .nationalityCode("VN")
                                    .build());
            c.setCustomerCode("KH" + String.format("%06d", c.getId()));
        }
    }

    public void sync(User user) {
        provision(user);
        customers
                .findByUser_Id(user.getId())
                .ifPresent(
                        c -> {
                            c.setFullName(user.getFullName());
                            c.setEmail(user.getEmail());
                            c.setPhone(user.getPhone());
                        });
    }

    private String temp() {
        return "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
