package com.example.hotelmanagement.web.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotelmanagement.modules.auth.repository.RefreshTokenRepository;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.repository.RoleRepository;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import java.util.Set;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminWebSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUpUsers() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role userRole = roleRepository.findByCode("USER").orElseThrow();

        userRepository.save(User.builder()
                .fullName("Portal Admin")
                .email("portal.admin@example.com")
                .passwordHash(passwordEncoder.encode("AdminPortal123"))
                .status(UserStatus.ACTIVE)
                .roles(Set.of(adminRole, userRole))
                .build());

        userRepository.save(User.builder()
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
        mockMvc.perform(post("/admin/login")
                        .param("email", "portal.admin@example.com")
                        .param("password", "AdminPortal123"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowAdminFormLoginAndRenderDashboardAndUserList() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/login")
                        .with(csrf())
                        .param("email", "portal.admin@example.com")
                        .param("password", "AdminPortal123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mockMvc.perform(get("/admin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("System Overview")));

        mockMvc.perform(get("/admin/users").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("portal.admin@example.com")));
    }

    @Test
    void shouldDenyNonAdminUserFromLoggingIntoAdminPortalOrAccessingAdminRoutes() throws Exception {
        // Non-admin form login is rejected and logged out immediately
        mockMvc.perform(post("/admin/login")
                        .with(csrf())
                        .param("email", "regular.user@example.com")
                        .param("password", "RegularPass123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?accessDenied=true"));

        // Direct session access with ROLE_USER gets 403 Forbidden
        mockMvc.perform(get("/admin/users")
                        .with(user("regular.user@example.com").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldExposeHealthCheckAndOpenApiDocsInTestProfile() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}
