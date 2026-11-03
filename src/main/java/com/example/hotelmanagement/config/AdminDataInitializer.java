package com.example.hotelmanagement.config;

import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.service.RoleService;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.security.authorization.SecurityConstants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "app.seed.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdminDataInitializer implements ApplicationRunner {

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final RoleService roleService;
    private final PasswordEncoder passwordEncoder;
    private final com.example.hotelmanagement.modules.user.service.ProfileProvisioningService
            profiles;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Seed.Admin adminProps = appProperties.getSeed().getAdmin();
        if (!StringUtils.hasText(adminProps.getEmail())
                || !StringUtils.hasText(adminProps.getPassword())) {
            log.warn(
                    "Admin seed is enabled, but ADMIN_SEED_EMAIL or ADMIN_SEED_PASSWORD is missing."
                            + " Skipping seed.");
            return;
        }
        if (adminProps.getPassword().trim().length() < 8) {
            log.warn("ADMIN_SEED_PASSWORD must be at least 8 characters. Skipping admin seed.");
            return;
        }

        String normalizedEmail = adminProps.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.info("Seed admin user '{}' already exists. No changes made.", normalizedEmail);
            return;
        }

        Role adminRole = roleService.getRequiredByCode(SecurityConstants.ROLE_ADMIN);
        Role userRole = roleService.getRequiredByCode(SecurityConstants.ROLE_USER);
        Set<Role> roles = new LinkedHashSet<>();
        roles.add(adminRole);
        roles.add(userRole);

        User admin =
                User.builder()
                        .fullName(
                                StringUtils.hasText(adminProps.getFullName())
                                        ? adminProps.getFullName().trim()
                                        : "System Administrator")
                        .email(normalizedEmail)
                        .passwordHash(passwordEncoder.encode(adminProps.getPassword()))
                        .status(UserStatus.ACTIVE)
                        .roles(roles)
                        .build();

        profiles.provision(userRepository.save(admin));
        log.info("Development initial ADMIN account created for email: {}", normalizedEmail);
    }
}
