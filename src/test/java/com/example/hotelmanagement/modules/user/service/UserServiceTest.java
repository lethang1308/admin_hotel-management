package com.example.hotelmanagement.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.DuplicateResourceException;
import com.example.hotelmanagement.config.AppProperties;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserStatusRequest;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.mapper.UserMapper;
import com.example.hotelmanagement.modules.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;

    @Mock private RoleService roleService;

    @Mock private CredentialRevocationService revocations;

    @Mock private ProfileProvisioningService profiles;
    @Mock private com.example.hotelmanagement.modules.audit.service.AuditLogService audit;

    @Mock private PasswordEncoder passwordEncoder;

    @Mock private com.example.hotelmanagement.common.util.CmsMessages messages;

    private UserService userService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(messages.error(any(String.class)))
                .thenAnswer(inv -> new BusinessException(inv.getArgument(0, String.class)));
        AppProperties appProperties = new AppProperties();
        appProperties.getPagination().setMaxPageSize(50);
        userService =
                new UserService(
                        userRepository,
                        roleService,
                        revocations,
                        profiles,
                        audit,
                        passwordEncoder,
                        new UserMapper(),
                        appProperties,
                        messages);
    }

    @Test
    void shouldCreateUserWithNormalizedEmailAndEncodedPassword() {
        Role userRole = Role.builder().id(2L).code("USER").name("Standard User").build();
        when(userRepository.existsByEmailIgnoreCase("new.user@example.com")).thenReturn(false);
        when(roleService.resolveRoles(any(), eq("USER"))).thenReturn(Set.of(userRole));
        when(passwordEncoder.encode("SecurePass123")).thenReturn("$2a$10$encoded");
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        inv -> {
                            User u = inv.getArgument(0);
                            u.setId(10L);
                            return u;
                        });

        CreateUserRequest request =
                CreateUserRequest.builder()
                        .fullName("  New User ")
                        .email("  NEW.USER@Example.com ")
                        .password("SecurePass123")
                        .build();

        UserResponse created = userService.createUser(request);

        assertThat(created.getId()).isEqualTo(10L);
        assertThat(created.getFullName()).isEqualTo("New User");
        assertThat(created.getEmail()).isEqualTo("new.user@example.com");
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getRoles()).containsExactly("USER");
    }

    @Test
    void shouldRejectDuplicateEmailOnCreate() {
        when(userRepository.existsByEmailIgnoreCase("dup@example.com")).thenReturn(true);

        CreateUserRequest request =
                CreateUserRequest.builder()
                        .fullName("Dup User")
                        .email("dup@example.com")
                        .password("SecurePass123")
                        .build();

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void shouldPreventAdminFromDeactivatingOwnAccount() {
        User admin =
                User.builder()
                        .id(1L)
                        .fullName("Admin")
                        .email("admin@example.com")
                        .status(UserStatus.ACTIVE)
                        .build();
        when(userRepository.findWithRolesById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(
                        () ->
                                userService.updateUserStatus(
                                        1L,
                                        new UpdateUserStatusRequest(UserStatus.INACTIVE),
                                        "admin@example.com"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRevokeRefreshTokensWhenDeactivatingAnotherUser() {
        User target =
                User.builder()
                        .id(5L)
                        .fullName("Staff")
                        .email("staff@example.com")
                        .status(UserStatus.ACTIVE)
                        .roles(Set.of(Role.builder().code("USER").build()))
                        .build();
        when(userRepository.findWithRolesById(5L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response =
                userService.updateUserStatus(
                        5L, new UpdateUserStatusRequest(UserStatus.INACTIVE), "admin@example.com");

        assertThat(response.getStatus()).isEqualTo(UserStatus.INACTIVE);
        verify(revocations).revoke(target);
    }

    @Test
    void shouldRejectDisallowedSortFieldAndExcessivePageSize() {
        assertThatThrownBy(() -> userService.getUsers(0, 10, "passwordHash,asc", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported sort field");

        assertThatThrownBy(() -> userService.getUsers(0, 500, "createdAt,desc", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Page size");
    }
}
