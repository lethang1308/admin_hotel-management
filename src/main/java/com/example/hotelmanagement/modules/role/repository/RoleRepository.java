package com.example.hotelmanagement.modules.role.repository;

import com.example.hotelmanagement.modules.role.entity.Role;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code='ADMIN'")
    Optional<Role> lockAdminRole();

    List<Role> findByCodeIn(Collection<String> codes);

    boolean existsByCode(String code);
}
