package com.example.hotelmanagement.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotelmanagement.modules.auth.repository.RefreshTokenRepository;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.repository.RoleRepository;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.repository.UserRepository;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminWebSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired private UserRepository userRepository;

    @Autowired private RoleRepository roleRepository;

    @Autowired private RefreshTokenRepository refreshTokenRepository;

    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUpUsers() {
        com.example.hotelmanagement.support.CmsTestData.clear(jdbc);

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role userRole = roleRepository.findByCode("USER").orElseThrow();

        userRepository.save(
                User.builder()
                        .fullName("Portal Admin")
                        .email("portal.admin@example.com")
                        .passwordHash(passwordEncoder.encode("AdminPortal123"))
                        .status(UserStatus.ACTIVE)
                        .roles(Set.of(adminRole, userRole))
                        .build());

        userRepository.save(
                User.builder()
                        .fullName("Regular User")
                        .email("regular.user@example.com")
                        .passwordHash(passwordEncoder.encode("RegularPass123"))
                        .status(UserStatus.ACTIVE)
                        .roles(Set.of(userRole))
                        .build());
    }

    @Test
    void shouldRedirectUnauthenticatedAdminRequestToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void shouldEnforceCsrfOnAdminFormLogin() throws Exception {
        mockMvc.perform(
                        post("/admin/login")
                                .param("email", "portal.admin@example.com")
                                .param("password", "AdminPortal123"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowAdminFormLoginAndRenderDashboardAndUserListInDefaultVietnameseAndEnglish()
            throws Exception {
        MvcResult loginResult =
                mockMvc.perform(
                                post("/admin/login")
                                        .with(csrf())
                                        .param("email", "portal.admin@example.com")
                                        .param("password", "AdminPortal123"))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/admin"))
                        .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        // 1. Verify Default Locale is Vietnamese
        mockMvc.perform(get("/admin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Tổng quan hệ thống")))
                .andExpect(content().string(Matchers.containsString("Bảng điều khiển")))
                .andExpect(content().string(Matchers.containsString("lang=vi")))
                .andExpect(content().string(Matchers.containsString("lang=en")));

        mockMvc.perform(get("/admin/users").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Quản lý người dùng")))
                .andExpect(content().string(Matchers.containsString("portal.admin@example.com")));

        mockMvc.perform(get("/admin/users/create").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Tạo tài khoản mới")));

        User adminUser =
                userRepository.findByEmailIgnoreCase("portal.admin@example.com").orElseThrow();
        mockMvc.perform(get("/admin/users/" + adminUser.getId() + "/edit").session(session))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(Matchers.containsString("Hồ sơ &amp; Phân quyền vai trò")));

        mockMvc.perform(get("/admin/roles").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Vai trò hệ thống (RBAC)")));

        // 2. Verify Switching to English (?lang=en)
        mockMvc.perform(get("/admin?lang=en").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("System Overview")))
                .andExpect(content().string(Matchers.containsString("Dashboard")));

        mockMvc.perform(get("/admin/users?lang=en").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("User Management")));

        mockMvc.perform(get("/admin/users/create?lang=en").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Create User Account")));

        mockMvc.perform(get("/admin/roles?lang=en").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("System Roles (RBAC)")));
    }

    @Test
    void shouldServeMazerLoginPageAndStaticAssetsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("auth-left")))
                .andExpect(content().string(Matchers.containsString("Đăng nhập")));

        mockMvc.perform(get("/admin/login?lang=en"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Log in")));

        mockMvc.perform(get("/admin/assets/compiled/css/app.css")).andExpect(status().isOk());

        mockMvc.perform(get("/admin/assets/compiled/js/app.js")).andExpect(status().isOk());

        mockMvc.perform(get("/admin/assets/static/images/logo/logo.svg"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldDenyNonAdminUserFromLoggingIntoAdminPortalOrAccessingAdminRoutes() throws Exception {
        // Non-admin form login is rejected and logged out immediately
        mockMvc.perform(
                        post("/admin/login")
                                .with(csrf())
                                .param("email", "regular.user@example.com")
                                .param("password", "RegularPass123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?accessDenied=true"));

        // Direct session access with ROLE_USER gets 403 Forbidden
        mockMvc.perform(get("/admin/users").with(user("regular.user@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldExposeHealthCheckAndOpenApiDocsInTestProfile() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}
