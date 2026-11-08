package com.example.hotelmanagement.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hotelmanagement.common.util.TokenHashUtils;
import com.example.hotelmanagement.modules.auth.dto.LoginRequest;
import com.example.hotelmanagement.modules.auth.dto.RefreshTokenRequest;
import com.example.hotelmanagement.modules.auth.dto.RegisterRequest;
import com.example.hotelmanagement.modules.auth.repository.RefreshTokenRepository;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.role.repository.RoleRepository;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserStatusRequest;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndRbacIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired private JsonMapper jsonMapper;

    @Autowired private UserRepository userRepository;

    @Autowired private RoleRepository roleRepository;

    @Autowired private RefreshTokenRepository refreshTokenRepository;

    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        com.example.hotelmanagement.support.CmsTestData.clear(jdbc);
    }

    @Test
    void shouldRegisterLoginFetchProfileAndEnforceHashedRefreshTokenRotation() throws Exception {
        RegisterRequest registerReq =
                RegisterRequest.builder()
                        .fullName("John Customer")
                        .email("john@example.com")
                        .phone("0912345678")
                        .password("Welcome123")
                        .build();

        MvcResult registerRes =
                mockMvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(jsonMapper.writeValueAsString(registerReq)))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("X-Correlation-Id"))
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.user.email").value("john@example.com"))
                        .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
                        .andExpect(jsonPath("$.data.user.password").doesNotExist())
                        .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                        .andReturn();

        JsonNode registerJson = jsonMapper.readTree(registerRes.getResponse().getContentAsString());
        String rawRefresh1 = registerJson.at("/data/refreshToken").asString();

        // Verify refresh token is stored as SHA-256 hash, never plaintext
        String expectedHash = TokenHashUtils.sha256Hex(rawRefresh1);
        assertThat(refreshTokenRepository.findByTokenHash(expectedHash)).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(rawRefresh1)).isEmpty();

        // Login with valid credentials -> 200 OK
        LoginRequest loginReq =
                LoginRequest.builder().email("john@example.com").password("Welcome123").build();

        MvcResult loginRes =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(jsonMapper.writeValueAsString(loginReq)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                        .andReturn();

        JsonNode loginJson = jsonMapper.readTree(loginRes.getResponse().getContentAsString());
        String accessToken = loginJson.at("/data/accessToken").asString();
        String loginRefresh1 = loginJson.at("/data/refreshToken").asString();

        // Access /api/v1/auth/me with Bearer token -> 200 OK
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("john@example.com"));

        // Rotate refresh token -> 200 OK with new refresh token
        MvcResult refreshRes =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                jsonMapper.writeValueAsString(
                                                        new RefreshTokenRequest(loginRefresh1))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andReturn();

        String loginRefresh2 =
                jsonMapper
                        .readTree(refreshRes.getResponse().getContentAsString())
                        .at("/data/refreshToken")
                        .asString();
        assertThat(loginRefresh2).isNotEqualTo(loginRefresh1);

        // Replay already-rotated refresh token -> 401 TOKEN_REVOKED and revokes family!
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                new RefreshTokenRequest(loginRefresh1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_REVOKED"));

        // Family revocation means loginRefresh2 is now also revoked!
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                new RefreshTokenRequest(loginRefresh2))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_REVOKED"));
    }

    @Test
    void shouldReturn401OnWrongPasswordAnd403OnDisabledAccount() throws Exception {
        Role userRole = roleRepository.findByCode("USER").orElseThrow();
        User activeUser =
                userRepository.save(
                        User.builder()
                                .fullName("Active Member")
                                .email("active@example.com")
                                .passwordHash(passwordEncoder.encode("CorrectPass1"))
                                .status(UserStatus.ACTIVE)
                                .roles(Set.of(userRole))
                                .build());

        // Wrong password -> 401 INVALID_CREDENTIALS
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                new LoginRequest(
                                                        "active@example.com", "WrongPass99"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));

        // Login properly first to obtain a valid JWT
        MvcResult loginRes =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                jsonMapper.writeValueAsString(
                                                        new LoginRequest(
                                                                "active@example.com",
                                                                "CorrectPass1"))))
                        .andExpect(status().isOk())
                        .andReturn();
        String jwt =
                jsonMapper
                        .readTree(loginRes.getResponse().getContentAsString())
                        .at("/data/accessToken")
                        .asString();

        // Deactivate user in DB
        activeUser.setStatus(UserStatus.INACTIVE);
        userRepository.save(activeUser);

        // Login with deactivated account -> 403 ACCOUNT_DISABLED
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                new LoginRequest(
                                                        "active@example.com", "CorrectPass1"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DISABLED"));

        // Existing unexpired JWT is immediately rejected -> 403 ACCOUNT_DISABLED
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DISABLED"));
    }

    @Test
    void shouldReturn400ProblemDetailWhenValidationFails() throws Exception {
        RegisterRequest invalidReq =
                RegisterRequest.builder()
                        .fullName("")
                        .email("not-an-email")
                        .password("weak")
                        .build();

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.fullName").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void shouldEnforceRbacBetweenStandardUserAndAdminOnUserManagementApi() throws Exception {
        Role userRole = roleRepository.findByCode("USER").orElseThrow();
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();

        userRepository.save(
                User.builder()
                        .fullName("Standard Member")
                        .email("member@example.com")
                        .passwordHash(passwordEncoder.encode("MemberPass1"))
                        .status(UserStatus.ACTIVE)
                        .roles(Set.of(userRole))
                        .build());

        userRepository.save(
                User.builder()
                        .fullName("Super Admin")
                        .email("admin@example.com")
                        .passwordHash(passwordEncoder.encode("AdminPass1"))
                        .status(UserStatus.ACTIVE)
                        .roles(Set.of(adminRole, userRole))
                        .build());

        String userToken = obtainToken("member@example.com", "MemberPass1");
        String adminToken = obtainToken("admin@example.com", "AdminPass1");

        // Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));

        // USER role -> 403 Forbidden
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        // ADMIN role -> 200 OK with paginated envelope
        mockMvc.perform(
                        get("/api/v1/users?page=0&size=10&sort=createdAt,desc")
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // ADMIN creates user -> 201 Created
        CreateUserRequest createReq =
                CreateUserRequest.builder()
                        .fullName("Created Staff")
                        .email("staff@example.com")
                        .password("StaffPass123")
                        .status(UserStatus.ACTIVE)
                        .roles(Set.of("USER"))
                        .build();

        MvcResult createdRes =
                mockMvc.perform(
                                post("/api/v1/users")
                                        .header("Authorization", "Bearer " + adminToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(jsonMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.email").value("staff@example.com"))
                        .andReturn();

        long staffId =
                jsonMapper
                        .readTree(createdRes.getResponse().getContentAsString())
                        .at("/data/id")
                        .asLong();

        // ADMIN updates status -> 200 OK
        mockMvc.perform(
                        patch("/api/v1/users/" + staffId + "/status")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        jsonMapper.writeValueAsString(
                                                new UpdateUserStatusRequest(UserStatus.LOCKED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
    }

    private String obtainToken(String email, String password) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                jsonMapper.writeValueAsString(
                                                        new LoginRequest(email, password))))
                        .andExpect(status().isOk())
                        .andReturn();
        return jsonMapper
                .readTree(result.getResponse().getContentAsString())
                .at("/data/accessToken")
                .asString();
    }
}
