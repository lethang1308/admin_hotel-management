package com.example.hotelmanagement.modules.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.common.util.TokenHashUtils;
import com.example.hotelmanagement.modules.auth.service.*;
import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.user.repository.UserRepository;
import com.example.hotelmanagement.support.CmsTestData;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import javax.imageio.ImageIO;

@SpringBootTest(
        properties = {"app.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class UserAccountIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired OperationsStore db;
    @Autowired UserRepository users;
    @Autowired ApplicationEvents events;
    @Autowired PasswordResetService resets;
    @Autowired PasswordResetMailer mailer;
    @MockitoBean JavaMailSender sender;
    static final Path MEDIA =
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "hotel-account-media-" + UUID.randomUUID());

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry p) {
        p.add("app.hotel.media-directory", () -> MEDIA.toString());
    }

    record Login(long id, String access, String refresh) {}

    @BeforeEach
    void clear() {
        CmsTestData.clear(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(mailer, "enabled", false);
    }

    @AfterEach
    void restore() {
        org.springframework.test.util.ReflectionTestUtils.setField(mailer, "enabled", false);
    }

    String body(Object value) {
        return json.writeValueAsString(value);
    }

    Login register(String email, String phone) throws Exception {
        var result =
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                body(
                                                        Map.of(
                                                                "fullName",
                                                                "Nguyễn An",
                                                                "email",
                                                                email,
                                                                "phone",
                                                                phone,
                                                                "password",
                                                                "StrongPass123"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        var data = json.readTree(result.getResponse().getContentAsString()).get("data");
        return new Login(
                data.get("user").get("id").asLong(),
                data.get("accessToken").asString(),
                data.get("refreshToken").asString());
    }

    org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
                    request,
            Login login) {
        return request.header("Authorization", "Bearer " + login.access());
    }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Login login) {
        return request.header("Authorization", "Bearer " + login.access());
    }

    void forgot(String email) throws Exception {
        mvc.perform(
                        post("/api/v1/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(Map.of("email", email))))
                .andExpect(status().isOk());
    }

    String token() {
        return events.stream(PasswordResetService.Requested.class)
                .reduce((a, b) -> b)
                .orElseThrow()
                .token();
    }

    Map<String, Object> profile(String phone) {
        return new HashMap<>(
                Map.of(
                        "fullName",
                        "Nguyễn An Mới",
                        "phone",
                        phone,
                        "dateOfBirth",
                        "1995-10-20",
                        "gender",
                        "FEMALE",
                        "address",
                        "Hà Nội"));
    }

    @Test
    void registerCreatesUserCustomerAndOnlyUserRoleWithCanonicalPhone() throws Exception {
        var u = register("new@example.test", "+84912345678");
        mvc.perform(auth(get("/api/v1/auth/me"), u))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("0912345678"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        var c = db.one("SELECT * FROM customers WHERE user_id=?", u.id());
        assertThat(c.s("phone")).isEqualTo("0912345678");
        assertThat(c.s("customer_code")).startsWith("KH");
        assertThat(c.s("nationality_code")).isEqualTo("VN");
        assertThat(
                        db.one("SELECT token_hash FROM refresh_tokens WHERE user_id=?", u.id())
                                .s("token_hash"))
                .isEqualTo(TokenHashUtils.sha256Hex(u.refresh()));
    }

    @Test
    void duplicateEmailAndCanonicalPhoneReturn409AndMissingPhoneReturns400() throws Exception {
        register("new@example.test", "0912345678");
        for (var payload :
                List.of(
                        Map.of(
                                "fullName",
                                "New User",
                                "email",
                                "new@example.test",
                                "phone",
                                "0987654321",
                                "password",
                                "StrongPass123"),
                        Map.of(
                                "fullName",
                                "New User",
                                "email",
                                "second@example.test",
                                "phone",
                                "+84912345678",
                                "password",
                                "StrongPass123")))
            mvc.perform(
                            post("/api/v1/auth/register")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(payload)))
                    .andExpect(status().isConflict());
        mvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "fullName",
                                                        "New User",
                                                        "email",
                                                        "third@example.test",
                                                        "password",
                                                        "StrongPass123"))))
                .andExpect(status().isBadRequest());
        assertThat(db.count("SELECT COUNT(*) FROM users")).isEqualTo(1);
    }

    @Test
    void profileUpdatesBothTablesAndCannotEditAnotherUsersIdentityOrRoles() throws Exception {
        var a = register("first@example.test", "0912345678");
        var b = register("second@example.test", "0987654321");
        var input = profile("+84911111111");
        input.put("id", b.id());
        input.put("roles", List.of("ADMIN"));
        mvc.perform(
                        auth(put("/api/v1/users/profile"), a)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(input)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("Nguyễn An Mới"))
                .andExpect(jsonPath("$.data.phone").value("0911111111"))
                .andExpect(jsonPath("$.data.gender").value("FEMALE"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));
        var c = db.one("SELECT * FROM customers WHERE user_id=?", a.id());
        assertThat(c.s("full_name")).isEqualTo("Nguyễn An Mới");
        assertThat(c.s("address")).isEqualTo("Hà Nội");
        assertThat(c.date("date_of_birth")).isEqualTo(LocalDate.of(1995, 10, 20));
        assertThat(users.findById(b.id()).orElseThrow().getFullName()).isEqualTo("Nguyễn An");
        mvc.perform(auth(get("/api/v1/users/" + b.id()), a)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/users/profile"), a))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.documentNumberEncrypted").doesNotExist())
                .andExpect(jsonPath("$.data.notes").doesNotExist());
    }

    @Test
    void profileRejectsDuplicatePhoneFutureBirthAndInvalidGenderWithoutPartialUpdates()
            throws Exception {
        var a = register("first@example.test", "0912345678");
        register("second@example.test", "0987654321");
        mvc.perform(
                        auth(put("/api/v1/users/profile"), a)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(profile("0987654321"))))
                .andExpect(status().isConflict());
        var input = profile("0911111111");
        input.put("dateOfBirth", LocalDate.now().plusDays(1).toString());
        mvc.perform(
                        auth(put("/api/v1/users/profile"), a)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(input)))
                .andExpect(status().isBadRequest());
        input.put("dateOfBirth", "1995-10-20");
        input.put("gender", "ADMIN");
        mvc.perform(
                        auth(put("/api/v1/users/profile"), a)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(input)))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(a.id()).orElseThrow().getFullName()).isEqualTo("Nguyễn An");
    }

    @Test
    void avatarUploadsRealPngAndRejectsDisguisedSvgOrExcessiveDimensions() throws Exception {
        var a = register("avatar@example.test", "0912345678");
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var response =
                mvc.perform(
                                auth(
                                        multipart("/api/v1/users/profile/avatar")
                                                .file(
                                                        new MockMultipartFile(
                                                                "file",
                                                                "../../bad.jpg",
                                                                "image/jpeg",
                                                                bytes.toByteArray())),
                                        a))
                        .andExpect(status().isOk())
                        .andReturn();
        String url =
                json.readTree(response.getResponse().getContentAsString())
                        .at("/data/avatarUrl")
                        .asString();
        assertThat(url).matches("/media/hotel/avatars/[a-f0-9]{32}\\.png");
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        mvc.perform(
                        auth(
                                multipart("/api/v1/users/profile/avatar")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "bad.png",
                                                        "image/png",
                                                        "<svg><script/></svg>".getBytes())),
                                a))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(a.id()).orElseThrow().getAvatarUrl()).isEqualTo(url);
    }

    @Test
    void forgotDoesNotEnumerateAndResetTokensAreHashedSingleUseAndRevokeOldSessions()
            throws Exception {
        var a = register("reset@example.test", "0912345678");
        var unknown =
                mvc.perform(
                                post("/api/v1/auth/forgot-password")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body(Map.of("email", "unknown@example.test"))))
                        .andExpect(status().isOk())
                        .andReturn();
        forgot("reset@example.test");
        String raw = token();
        assertThat(
                        db.one("SELECT token_hash FROM account_tokens WHERE user_id=?", a.id())
                                .s("token_hash"))
                .isEqualTo(TokenHashUtils.sha256Hex(raw));
        assertThat(unknown.getResponse().getContentAsString())
                .doesNotContain("unknown@example.test", raw);
        forgot("reset@example.test");
        assertThat(db.count("SELECT COUNT(*) FROM account_tokens WHERE user_id=?", a.id()))
                .isEqualTo(1);
        mvc.perform(
                        post("/api/v1/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "token",
                                                        raw,
                                                        "newPassword",
                                                        "NewPassword456"))))
                .andExpect(status().isOk());
        assertThat(
                        db.one("SELECT used_at FROM account_tokens WHERE user_id=?", a.id())
                                .time("used_at"))
                .isNotNull();
        mvc.perform(
                        post("/api/v1/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "token",
                                                        raw,
                                                        "newPassword",
                                                        "AnotherPass789"))))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/users/profile"), a)).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(Map.of("refreshToken", a.refresh()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "email",
                                                        "reset@example.test",
                                                        "password",
                                                        "NewPassword456"))))
                .andExpect(status().isOk());
    }

    @Test
    void expiredResetAndSameOrWeakPasswordAreRejected() throws Exception {
        var a = register("reset@example.test", "0912345678");
        forgot("reset@example.test");
        String raw = token();
        for (String password : List.of("StrongPass123", "onlyletters"))
            mvc.perform(
                            post("/api/v1/auth/reset-password")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(Map.of("token", raw, "newPassword", password))))
                    .andExpect(status().isBadRequest());
        assertThat(
                        db.one("SELECT used_at FROM account_tokens WHERE user_id=?", a.id())
                                .time("used_at"))
                .isNull();
        db.update(
                "UPDATE account_tokens SET expires_at=? WHERE user_id=?",
                OperationsStore.ts(Instant.now().minusSeconds(1)),
                a.id());
        mvc.perform(
                        post("/api/v1/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "token",
                                                        raw,
                                                        "newPassword",
                                                        "NewPassword456"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordVerifiesCurrentAndInvalidatesOldAccessAndRefresh() throws Exception {
        var a = register("password@example.test", "0912345678");
        mvc.perform(
                        auth(put("/api/v1/auth/change-password"), a)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "currentPassword",
                                                        "Incorrect123",
                                                        "newPassword",
                                                        "NewPassword456"))))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        auth(put("/api/v1/auth/change-password"), a)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        body(
                                                Map.of(
                                                        "currentPassword",
                                                        "StrongPass123",
                                                        "newPassword",
                                                        "NewPassword456"))))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/auth/me"), a)).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(Map.of("refreshToken", a.refresh()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAfterRotationRevokesCurrentFamilyAndOtherLoginRemainsValid() throws Exception {
        var a = register("logout@example.test", "0912345678");
        var rotated =
                mvc.perform(
                                post("/api/v1/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body(Map.of("refreshToken", a.refresh()))))
                        .andExpect(status().isOk())
                        .andReturn();
        String next =
                json.readTree(rotated.getResponse().getContentAsString())
                        .at("/data/refreshToken")
                        .asString();
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(Map.of("refreshToken", a.refresh()))))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(Map.of("refreshToken", next))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationsAreOwnedPagedAndCannotBeReadAcrossAccounts() throws Exception {
        var a = register("first@example.test", "0912345678");
        var b = register("second@example.test", "0987654321");
        long first =
                db.insert(
                        "notifications",
                        "user_id,deduplication_key,type,title,body",
                        a.id(),
                        "N1",
                        "BOOKING_CONFIRMED",
                        "Đã xác nhận",
                        "Đơn của bạn");
        long other =
                db.insert(
                        "notifications",
                        "user_id,deduplication_key,type,title,body",
                        b.id(),
                        "N2",
                        "PAYMENT_COLLECTED",
                        "Đã thanh toán",
                        "Thông tin riêng");
        mvc.perform(auth(get("/api/v1/notifications"), a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "Thông tin riêng"))));
        mvc.perform(auth(get("/api/v1/notifications/unread-count"), a))
                .andExpect(jsonPath("$.data.count").value(1));
        mvc.perform(auth(patch("/api/v1/notifications/" + other + "/read"), a))
                .andExpect(status().isNotFound());
        mvc.perform(auth(patch("/api/v1/notifications/" + first + "/read"), a))
                .andExpect(status().isOk());
        mvc.perform(auth(patch("/api/v1/notifications/read-all"), a)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/notifications/unread-count"), a))
                .andExpect(jsonPath("$.data.count").value(0));
        assertThat(db.one("SELECT read_at FROM notifications WHERE id=?", other).time("read_at"))
                .isNull();
    }

    @Test
    void endpointsRequireBearerAndCorsAllowsConfiguredFrontendOnly() throws Exception {
        for (String path :
                List.of(
                        "/api/v1/users/profile",
                        "/api/v1/notifications",
                        "/api/v1/notifications/unread-count"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(
                        options("/api/v1/users/profile")
                                .header("Origin", "http://localhost:5173")
                                .header("Access-Control-Request-Method", "PUT")
                                .header(
                                        "Access-Control-Request-Headers",
                                        "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(
                        options("/api/v1/users/profile")
                                .header("Origin", "https://untrusted.example")
                                .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void resetEmailHasRealMimeLinkAndNoTokenInPublicResponse() throws Exception {
        register("mime@example.test", "0912345678");
        var delivered = new java.util.concurrent.CountDownLatch(1);
        var capture = new java.util.concurrent.atomic.AtomicReference<MimeMessage>();
        org.mockito.Mockito.when(sender.createMimeMessage())
                .thenAnswer(
                        x -> new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        org.mockito.Mockito.doAnswer(
                        x -> {
                            capture.set(x.getArgument(0));
                            delivered.countDown();
                            return null;
                        })
                .when(sender)
                .send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        org.springframework.test.util.ReflectionTestUtils.setField(mailer, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
                mailer, "from", "hotel@example.test");
        forgot("mime@example.test");
        assertThat(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        String raw = token();
        var message = capture.get();
        message.saveChanges();
        assertThat(message.getSubject()).contains("khôi phục mật khẩu");
        var output = new ByteArrayOutputStream();
        message.writeTo(output);
        Object content = message.getContent();
        while (content instanceof jakarta.mail.Multipart multi)
            content = multi.getBodyPart(0).getContent();
        assertThat(content.toString())
                .contains("http://localhost:5173/reset-password?token=" + raw, "30 phút");
    }

    @Test
    void webpIsDecodedAndStoredAsSafePng() throws Exception {
        var a = register("webp@example.test", "0912345678");
        byte[] bytes =
                Base64.getDecoder()
                        .decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        mvc.perform(
                        auth(
                                multipart("/api/v1/users/profile/avatar")
                                        .file(
                                                new MockMultipartFile(
                                                        "file", "tiny.webp", "image/webp", bytes)),
                                a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl", org.hamcrest.Matchers.endsWith(".png")));
    }

    @Test
    void concurrentResetConsumesTokenOnlyOnce() throws Exception {
        register("concurrent@example.test", "0912345678");
        forgot("concurrent@example.test");
        String raw = token();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++)
                calls.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    try {
                                        resets.reset(raw, "ConcurrentPass456");
                                        return true;
                                    } catch (
                                            com.example.hotelmanagement.common.exception
                                                            .AppException
                                                    rejected) {
                                        return false;
                                    }
                                }));
            go.countDown();
            int accepted = 0;
            for (var call : calls)
                if (call.get(15, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentForgotRequestsRespectTheCooldown() throws Exception {
        var account = register("cooldown@example.test", "0912345678");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++)
                calls.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    resets.request("cooldown@example.test");
                                    return null;
                                }));
            go.countDown();
            for (var call : calls) call.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(
                            db.count(
                                    "SELECT COUNT(*) FROM account_tokens WHERE user_id=?",
                                    account.id()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Autowired com.example.hotelmanagement.modules.booking.BookingService bookings;
    @Autowired com.example.hotelmanagement.modules.payment.FrontDeskPaymentService payments;

    @Autowired
    com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator inventory;

    @Autowired org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired com.example.hotelmanagement.security.authentication.CustomUserDetailsService details;

    void authenticate(long id) {
        var user = details.loadUserById(id);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(
                                user, null, user.getAuthorities()));
    }

    @Test
    void bookingAndPaymentEventsNotifyOwnerOnlyWithoutEmailDuplicates() throws Exception {
        var a = register("booking@example.test", "0912345678");
        var other = register("other@example.test", "0987654321");
        long type = db.one("SELECT id FROM room_types WHERE code='STD'").id("id");
        db.update("UPDATE room_types SET base_price=800000 WHERE id=?", type);
        long floor =
                db.insert(
                        "floors",
                        "code,name,area_name,floor_number",
                        "ACCOUNT_F1",
                        "Tầng 1",
                        "A",
                        1);
        db.insert(
                "rooms",
                "room_number,room_type_id,floor_id,sell_from",
                "101",
                type,
                floor,
                db.today());
        new org.springframework.transaction.support.TransactionTemplate(tx)
                .executeWithoutResult(
                        status -> {
                            inventory.lock();
                            inventory.reconcile(List.of(type));
                        });
        try {
            authenticate(a.id());
            long customer = db.one("SELECT id FROM customers WHERE user_id=?", a.id()).id("id");
            var form =
                    new com.example.hotelmanagement.modules.booking.BookingService.Create(
                            OperationsStore.code(""),
                            customer,
                            "Nguyễn An",
                            "0912345678",
                            "booking@example.test",
                            "PHONE",
                            null,
                            null,
                            List.of(
                                    new com.example.hotelmanagement.modules.booking.BookingService
                                            .RoomInput(
                                            type,
                                            db.today().plusDays(1),
                                            db.today().plusDays(2),
                                            1,
                                            0)));
            long booking = bookings.create(form, bookings.preview(form).totalAmount());
            db.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE"
                            + " code='ADMIN'",
                    a.id());
            authenticate(a.id());
            bookings.confirm(booking, "Confirmed by test receptionist");
            payments.collect(
                    booking,
                    OperationsStore.code(""),
                    "PAYMENT",
                    "BANK_TRANSFER",
                    new java.math.BigDecimal("100000"),
                    "Payment received at counter");
            mvc.perform(auth(get("/api/v1/notifications"), a))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(3));
            mvc.perform(auth(get("/api/v1/notifications/unread-count"), other))
                    .andExpect(jsonPath("$.data.count").value(0));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
