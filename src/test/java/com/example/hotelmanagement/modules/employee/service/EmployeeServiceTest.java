package com.example.hotelmanagement.modules.employee.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.hotelmanagement.common.util.CmsMessages;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.employee.dto.EmployeeForm;
import com.example.hotelmanagement.modules.employee.entity.Employee;
import com.example.hotelmanagement.modules.employee.repository.EmployeeRepository;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.modules.user.service.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {
    @Mock EmployeeRepository repository;
    @Mock UserRepository users;
    @Mock UserService userService;
    @Mock RoleService roles;
    @Mock CredentialRevocationService revocations;
    @Mock AuditLogService audit;
    @Mock CmsMessages messages;
    @InjectMocks EmployeeService service;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatedStaffPermissionCannotEditAnAdministratorAccount() {
        var target =
                User.builder()
                        .id(1L)
                        .email("admin@example.test")
                        .roles(Set.of(Role.builder().code("ADMIN").build()))
                        .build();
        when(repository.findById(2L))
                .thenReturn(Optional.of(Employee.builder().id(2L).user(target).build()));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "delegated",
                                null,
                                List.of(
                                        new SimpleGrantedAuthority("ROLE_RECEPTIONIST"),
                                        new SimpleGrantedAuthority("account.manage"))));
        assertThatThrownBy(() -> service.update(2L, new EmployeeForm()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(target.getEmail()).isEqualTo("admin@example.test");
        verifyNoInteractions(audit, revocations, userService);
    }
}
