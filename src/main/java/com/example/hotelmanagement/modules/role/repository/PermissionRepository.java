package com.example.hotelmanagement.modules.role.repository;

import com.example.hotelmanagement.modules.role.entity.Permission;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {}
