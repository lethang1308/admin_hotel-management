package com.example.hotelmanagement.modules.audit.service;

import com.example.hotelmanagement.modules.customer.entity.Customer;
import com.example.hotelmanagement.modules.employee.entity.Employee;
import com.example.hotelmanagement.modules.user.entity.User;

import java.util.*;

public final class AuditSnapshots {
    private AuditSnapshots() {}

    public static Map<String, Object> user(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("fullName", u.getFullName());
        m.put("email", u.getEmail());
        m.put("phone", u.getPhone());
        m.put("status", u.getStatus().name());
        m.put("lockedUntil", u.getLockedUntil());
        m.put("passwordChangedAt", u.getPasswordChangedAt());
        m.put("roles", u.getRoles().stream().map(r -> r.getCode()).sorted().toList());
        return m;
    }

    public static Map<String, Object> employee(Employee e) {
        Map<String, Object> m = new LinkedHashMap<>(user(e.getUser()));
        m.put("employeeCode", e.getEmployeeCode());
        m.put("position", e.getPosition());
        m.put("hiredOn", e.getHiredOn());
        m.put("endedOn", e.getEndedOn());
        return m;
    }

    public static Map<String, Object> customer(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerCode", c.getCustomerCode());
        m.put("fullName", c.getFullName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        m.put("dateOfBirth", c.getDateOfBirth());
        m.put("nationality", c.getNationalityCode());
        m.put("address", c.getAddress());
        m.put("notes", c.getNotes());
        m.put("documentType", c.getDocumentType());
        m.put("hasIdentityDocument", c.getDocumentNumberEncrypted() != null);
        return m;
    }
}
