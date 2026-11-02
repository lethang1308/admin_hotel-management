package com.example.hotelmanagement.modules.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hotelmanagement.config.JpaAuditingConfig;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.repository.RoleRepository;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = "spring.test.database.replace=none")
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, FlywayAutoConfiguration.class})
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void shouldPersistAuditedUserAndQueryWithSpecifications() {
        Role userRole = roleRepository.findByCode("USER").orElseThrow();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        assertThat(adminRole.getName()).isEqualTo("Administrator");

        User saved = userRepository.saveAndFlush(User.builder()
                .fullName("Alice Repository")
                .email("alice.repo@example.com")
                .passwordHash("$2a$10$dummyhash")
                .status(UserStatus.ACTIVE)
                .roles(Set.of(userRole))
                .build());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Page<User> found = userRepository.findAll(
                UserSpecifications.withFilters("alice", UserStatus.ACTIVE, "USER"),
                PageRequest.of(0, 10)
        );
        assertThat(found.getTotalElements()).isEqualTo(1);
        assertThat(found.getContent().get(0).getEmail()).isEqualTo("alice.repo@example.com");
    }
}
