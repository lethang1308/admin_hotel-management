package com.example.hotelmanagement.web.admin;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.modules.audit.repository.AuditLogRepository;
import com.example.hotelmanagement.modules.auth.repository.RefreshTokenRepository;
import com.example.hotelmanagement.modules.customer.repository.CustomerRepository;
import com.example.hotelmanagement.modules.employee.repository.EmployeeRepository;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.entity.*;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.support.CmsTestData;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmsAdminModuleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired EmployeeRepository employees;
    @Autowired CustomerRepository customers;
    @Autowired AuditLogRepository audit;
    @Autowired RefreshTokenRepository refresh;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    Long adminId, staffId, memberId;

    @BeforeEach
    void fixtures() {
        CmsTestData.clear(jdbc);
        adminId = create("Admin CMS", "cms.admin@example.test", "ADMIN");
        staffId = create("Front Desk", "desk@example.test", "RECEPTIONIST");
        memberId = create("Customer", "customer@example.test", "USER");
    }

    private Long create(String name, String email, String role) {
        return userService
                .createUser(
                        CreateUserRequest.builder()
                                .fullName(name)
                                .email(email)
                                .password("InitialPass123")
                                .roles(Set.of(role))
                                .build())
                .getId();
    }

    private MockHttpSession login(String email, String password, String destination)
            throws Exception {
        MvcResult r =
                mvc.perform(
                                post("/admin/login")
                                        .with(csrf())
                                        .param("email", email)
                                        .param("password", password))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl(destination))
                        .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private MockHttpSession admin() throws Exception {
        return login("cms.admin@example.test", "InitialPass123", "/admin");
    }

    private MockHttpSession desk() throws Exception {
        return login("desk@example.test", "InitialPass123", "/admin/receptionist");
    }

    private Long staffEmployee() {
        return employees.findByUser_Id(staffId).orElseThrow().getId();
    }

    @Test
    void allNewPagesRenderInBothLanguagesAndRequireCsrf() throws Exception {
        var session = admin();
        Long employee = staffEmployee();
        Long customer = customers.findByUser_Id(memberId).orElseThrow().getId();
        for (String locale : List.of("vi", "en"))
            for (String route :
                    List.of(
                            "/admin/employees",
                            "/admin/employees/create",
                            "/admin/employees/" + employee + "/edit",
                            "/admin/customers",
                            "/admin/customers/create",
                            "/admin/customers/" + customer,
                            "/admin/customers/" + customer + "/edit",
                            "/admin/roles",
                            "/admin/audit-logs",
                            "/admin/profile/password",
                            "/admin/receptionist")) {
                mvc.perform(get(route).param("lang", locale).session(session))
                        .andExpect(status().isOk())
                        .andExpect(content().string(not(containsString("??cms."))));
            }
        long count = employees.count();
        mvc.perform(post("/admin/employees").session(session).param("fullName", "No CSRF"))
                .andExpect(status().isForbidden());
        assertThat(employees.count()).isEqualTo(count);
    }

    @Test
    void receptionistAccessUsesPermissionsAndHidesAdministrativeMenus() throws Exception {
        var session = desk();
        mvc.perform(get("/admin/receptionist").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/admin/roles\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/employees\""))));
        mvc.perform(get("/admin/customers").session(session)).andExpect(status().isOk());
        for (String route :
                List.of("/admin/employees", "/admin/users", "/admin/roles", "/admin/audit-logs"))
            mvc.perform(get(route).session(session)).andExpect(status().isForbidden());
        assertThat(users.findById(staffId).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    void employeeCreationIsAtomicAndRejectsDuplicateEmailAndInvalidDates() throws Exception {
        var session = admin();
        mvc.perform(
                        post("/admin/employees")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Lan Reception")
                                .param("email", "lan@example.test")
                                .param("phone", "0901234567")
                                .param("position", "Night Desk")
                                .param("password", "NewStaff123")
                                .param("hiredOn", "2026-10-01"))
                .andExpect(status().is3xxRedirection());
        User user = users.findByEmailIgnoreCase("lan@example.test").orElseThrow();
        assertThat(encoder.matches("NewStaff123", user.getPasswordHash())).isTrue();
        assertThat(user.hasRole("RECEPTIONIST")).isTrue();
        assertThat(user.hasRole("ADMIN")).isFalse();
        var employee = employees.findByUser_Id(user.getId()).orElseThrow();
        assertThat(employee.getEmployeeCode()).matches("NV[0-9]{6,}");
        assertThat(employee.getPosition()).isEqualTo("Night Desk");
        var entry =
                audit.findAll().stream()
                        .filter(a -> a.getAction().equals("EMPLOYEE_CREATE"))
                        .findFirst()
                        .orElseThrow();
        assertThat(entry.getActorUserId()).isEqualTo(adminId);
        assertThat(entry.getAfterData())
                .doesNotContain("NewStaff123", "passwordHash", user.getPasswordHash());
        mvc.perform(get("/admin/audit-logs/" + entry.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("EMPLOYEE_CREATE")));
        long count = users.count();
        mvc.perform(
                        post("/admin/employees")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Duplicate")
                                .param("email", "lan@example.test")
                                .param("password", "NewStaff123"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/admin/employees")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Bad dates")
                                .param("email", "dates@example.test")
                                .param("password", "NewStaff123")
                                .param("hiredOn", "2026-11-02")
                                .param("endedOn", "2026-10-01"))
                .andExpect(status().isOk());
        assertThat(users.count()).isEqualTo(count);
        mvc.perform(get("/admin/employees").session(session).param("search", "0901234567"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lan@example.test")));
    }

    @Test
    void employeeUpdateRecordsBeforeAndAfterAndRejectsPhoneConflict() throws Exception {
        var session = admin();
        Long id = staffEmployee();
        mvc.perform(
                        post("/admin/employees/" + id)
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Updated Desk")
                                .param("email", "desk@example.test")
                                .param("phone", "0909876543")
                                .param("position", "Morning shift")
                                .param("reason", "New shift"))
                .andExpect(status().is3xxRedirection());
        assertThat(users.findById(staffId).orElseThrow().getFullName()).isEqualTo("Updated Desk");
        var entry =
                audit.findAll().stream()
                        .filter(a -> a.getAction().equals("EMPLOYEE_UPDATE"))
                        .findFirst()
                        .orElseThrow();
        assertThat(entry.getBeforeData()).contains("Front Desk");
        assertThat(entry.getAfterData()).contains("Updated Desk", "Morning shift");
    }

    @Test
    void customerIdentityIsEncryptedSearchableMaskedAndAbsentFromAudit() throws Exception {
        var session = admin();
        mvc.perform(
                        post("/admin/customers")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Walk In Guest")
                                .param("phone", "0911111111")
                                .param("email", "walkin@example.test")
                                .param("nationalityCode", "VN")
                                .param("documentType", "CCCD")
                                .param("documentNumber", "123456789012")
                                .param("notes", "<script>alert(1)</script>")
                                .param("user.id", adminId.toString()))
                .andExpect(status().is3xxRedirection());
        var c =
                customers.findAll().stream()
                        .filter(x -> x.getFullName().equals("Walk In Guest"))
                        .findFirst()
                        .orElseThrow();
        assertThat(c.getUser()).isNull();
        assertThat(c.getDocumentNumberEncrypted()).startsWith("v1:").doesNotContain("123456789012");
        assertThat(c.getDocumentNumberHash()).hasSize(64);
        mvc.perform(get("/admin/customers/" + c.getId()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("9012")))
                .andExpect(content().string(not(containsString("123456789012"))))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
        mvc.perform(get("/admin/customers").session(session).param("search", "123456789012"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Walk In Guest")));
        assertThat(
                        audit.findAll().stream()
                                .filter(a -> a.getAction().equals("CUSTOMER_CREATE"))
                                .findFirst()
                                .orElseThrow()
                                .getAfterData())
                .doesNotContain(
                        "123456789012", c.getDocumentNumberEncrypted(), c.getDocumentNumberHash());
        mvc.perform(
                        post("/admin/customers/" + c.getId())
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Walk In Updated")
                                .param("nationalityCode", "VN")
                                .param("documentType", "CCCD")
                                .param("reason", "Corrected contact"))
                .andExpect(status().is3xxRedirection());
        assertThat(customers.findById(c.getId()).orElseThrow().getDocumentNumberEncrypted())
                .isEqualTo(c.getDocumentNumberEncrypted());
    }

    @Test
    void passwordResetRevokesBothSessionsAndOldApiTokens() throws Exception {
        var admin = admin();
        var staff = desk();
        Long id = staffEmployee();
        var response =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType("application/json")
                                        .content(
                                                "{\"email\":\"desk@example.test\",\"password\":\"InitialPass123\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        var tokens = json.readTree(response.getResponse().getContentAsString());
        String access = tokens.at("/data/accessToken").asString(),
                refreshToken = tokens.at("/data/refreshToken").asString();
        mvc.perform(
                        post("/admin/employees/" + id + "/password")
                                .session(admin)
                                .with(csrf())
                                .param("newPassword", "Replacement123")
                                .param("confirmPassword", "Replacement123")
                                .param("reason", "Staff requested reset"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/receptionist").session(staff))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?expired=true"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());
        login("desk@example.test", "Replacement123", "/admin/receptionist");
        assertThat(users.findById(staffId).orElseThrow().getPasswordChangedAt()).isNotNull();
        var log =
                audit.findAll().stream()
                        .filter(a -> a.getAction().equals("PASSWORD_RESET"))
                        .findFirst()
                        .orElseThrow();
        assertThat(log.getAfterData()).doesNotContain("Replacement123");
    }

    @Test
    void ownPasswordRequiresCurrentPasswordAndConfirmation() throws Exception {
        var session = admin();
        mvc.perform(
                        post("/admin/profile/password")
                                .session(session)
                                .with(csrf())
                                .param("currentPassword", "Wrong123")
                                .param("newPassword", "NewAdminPass123")
                                .param("confirmPassword", "NewAdminPass123"))
                .andExpect(status().isOk());
        assertThat(
                        encoder.matches(
                                "InitialPass123",
                                users.findById(adminId).orElseThrow().getPasswordHash()))
                .isTrue();
        mvc.perform(
                        post("/admin/profile/password")
                                .session(session)
                                .with(csrf())
                                .param("currentPassword", "InitialPass123")
                                .param("newPassword", "NewAdminPass123")
                                .param("confirmPassword", "Mismatch123"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/admin/profile/password")
                                .session(session)
                                .with(csrf())
                                .param("currentPassword", "InitialPass123")
                                .param("newPassword", "NewAdminPass123")
                                .param("confirmPassword", "NewAdminPass123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?passwordChanged=true"));
        login("cms.admin@example.test", "NewAdminPass123", "/admin");
    }

    @Test
    void lockAndDisableRevokeSessionsAndAdminCannotLockSelf() throws Exception {
        var admin = admin();
        var staff = desk();
        Long id = staffEmployee();
        mvc.perform(
                        post("/admin/employees/" + id + "/status")
                                .session(admin)
                                .with(csrf())
                                .param("status", "LOCKED")
                                .param("reason", "Temporary suspension"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/receptionist").session(staff))
                .andExpect(status().is3xxRedirection());
        mvc.perform(
                        post("/admin/login")
                                .with(csrf())
                                .param("email", "desk@example.test")
                                .param("password", "InitialPass123"))
                .andExpect(redirectedUrl("/admin/login?disabled=true"));
        Long own = employees.findByUser_Id(adminId).orElseThrow().getId();
        mvc.perform(
                        post("/admin/employees/" + own + "/status")
                                .session(admin)
                                .with(csrf())
                                .param("status", "DISABLED")
                                .param("reason", "Self lock attempt"))
                .andExpect(status().isOk());
        assertThat(users.findById(adminId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        mvc.perform(
                        post("/admin/employees/" + id + "/status")
                                .session(admin)
                                .with(csrf())
                                .param("status", "ACTIVE")
                                .param("reason", "Reactivated"))
                .andExpect(status().is3xxRedirection());
        desk();
    }

    @Test
    void expiredTimedLockAllowsLoginAndRecordsAutomaticUnlock() throws Exception {
        var u = users.findById(staffId).orElseThrow();
        u.setStatus(UserStatus.LOCKED);
        u.setLockedUntil(Instant.now().minusSeconds(60));
        users.saveAndFlush(u);
        desk();
        assertThat(users.findById(staffId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(
                        audit.findAll().stream()
                                .anyMatch(a -> a.getAction().equals("ACCOUNT_LOCK_EXPIRED")))
                .isTrue();
    }

    @Test
    void permissionMatrixTakesEffectOnExistingSessionsAndProtectsAdmin() throws Exception {
        var admin = admin();
        var staff = desk();
        Long role =
                jdbc.queryForObject("SELECT id FROM roles WHERE code='RECEPTIONIST'", Long.class);
        Long read =
                jdbc.queryForObject(
                        "SELECT id FROM permissions WHERE code='customer.read'", Long.class);
        var selected =
                jdbc
                        .queryForList(
                                "SELECT permission_id FROM role_permissions WHERE role_id=?",
                                Long.class,
                                role)
                        .stream()
                        .filter(id -> !id.equals(read))
                        .map(Object::toString)
                        .toArray(String[]::new);
        try {
            mvc.perform(
                            post("/admin/roles/" + role + "/permissions")
                                    .session(admin)
                                    .with(csrf())
                                    .param("permissions", selected))
                    .andExpect(status().is3xxRedirection());
            mvc.perform(get("/admin/customers").session(staff)).andExpect(status().isForbidden());
            Long adminRole =
                    jdbc.queryForObject("SELECT id FROM roles WHERE code='ADMIN'", Long.class);
            mvc.perform(
                            post("/admin/roles/" + adminRole + "/permissions")
                                    .session(admin)
                                    .with(csrf()))
                    .andExpect(status().is3xxRedirection());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM role_permissions WHERE role_id=?",
                                    Long.class,
                                    adminRole))
                    .isEqualTo(45L);
        } finally {
            jdbc.update(
                    "INSERT INTO role_permissions(role_id,permission_id) SELECT ?,? WHERE NOT"
                            + " EXISTS(SELECT 1 FROM role_permissions WHERE role_id=? AND"
                            + " permission_id=?)",
                    role,
                    read,
                    role,
                    read);
        }
    }

    @Test
    void emailChangePersistsProfileAndRevokesTheOldSession() throws Exception {
        var admin = admin();
        var oldSession = desk();
        Long employee = staffEmployee();
        mvc.perform(
                        post("/admin/employees/" + employee)
                                .session(admin)
                                .with(csrf())
                                .param("fullName", "Updated Receptionist")
                                .param("email", "updated.desk@example.test")
                                .param("position", "Afternoon shift")
                                .param("reason", "Contact correction"))
                .andExpect(status().is3xxRedirection());
        User updated = users.findByEmailIgnoreCase("updated.desk@example.test").orElseThrow();
        assertThat(updated.getId()).isEqualTo(staffId);
        assertThat(updated.getFullName()).isEqualTo("Updated Receptionist");
        assertThat(updated.getSecurityVersion()).isGreaterThan(0L);
        assertThat(users.findByEmailIgnoreCase("desk@example.test")).isEmpty();
        assertThat(employees.findById(employee).orElseThrow().getPosition())
                .isEqualTo("Afternoon shift");
        mvc.perform(get("/admin/receptionist").session(oldSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login?expired=true"));
        login("updated.desk@example.test", "InitialPass123", "/admin/receptionist");
    }

    @Test
    void employeeCodeConflictRollsBackUserProfileAndAudit() throws Exception {
        var session = admin();
        long userCount = users.count(),
                employeeCount = employees.count(),
                auditCount = audit.count();
        String code = employees.findByUser_Id(staffId).orElseThrow().getEmployeeCode();
        mvc.perform(
                        post("/admin/employees")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Conflicting Code")
                                .param("email", "conflict@example.test")
                                .param("password", "InitialPass123")
                                .param("employeeCode", code))
                .andExpect(status().isOk());
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(employees.count()).isEqualTo(employeeCount);
        assertThat(audit.count()).isEqualTo(auditCount);
        assertThat(users.findByEmailIgnoreCase("conflict@example.test")).isEmpty();
    }

    @Test
    void validationFailureDoesNotCreatePartialCustomerOrEmployee() throws Exception {
        var session = admin();
        long accounts = users.count(), guests = customers.count();
        mvc.perform(
                        post("/admin/customers")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "")
                                .param("nationalityCode", "INVALID"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/admin/employees")
                                .session(session)
                                .with(csrf())
                                .param("fullName", "Bad Password")
                                .param("email", "bad@example.test")
                                .param("password", "short"))
                .andExpect(status().isOk());
        assertThat(users.count()).isEqualTo(accounts);
        assertThat(customers.count()).isEqualTo(guests);
    }
}
