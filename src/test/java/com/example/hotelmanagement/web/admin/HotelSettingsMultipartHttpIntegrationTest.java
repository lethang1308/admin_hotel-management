package com.example.hotelmanagement.web.admin;

import static org.assertj.core.api.Assertions.*;

import com.example.hotelmanagement.modules.hotel.service.HotelMediaStorage;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.support.CmsTestData;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/** Real Tomcat request: MockMvc cannot detect multipart connector part-count limits. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.hotel.media-directory=target/hotel-multipart-http-media")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HotelSettingsMultipartHttpIntegrationTest {
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired HotelMediaStorage media;

    @Value("${local.server.port}")
    int port;

    private static String csrf(String html) {
        var m =
                Pattern.compile("<input(?=[^>]*name=\"_csrf\")(?=[^>]*value=\"([^\"]+)\")[^>]*>")
                        .matcher(html);
        if (!m.find()) throw new AssertionError("CSRF field missing");
        return m.group(1);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void hotelFormWithLogoAndMoreThanTenPartsSavesTaxAndPolicyIds() throws Exception {
        CmsTestData.clear(jdbc);
        users.createUser(
                CreateUserRequest.builder()
                        .fullName("HTTP Admin")
                        .email("multipart.admin@example.test")
                        .password("InitialPass123")
                        .roles(Set.of("ADMIN"))
                        .build());
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        String login =
                client.send(
                                HttpRequest.newBuilder(URI.create(url("/admin/login")))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body();
        String form =
                "email=multipart.admin%40example.test&password=InitialPass123&_csrf="
                        + URLEncoder.encode(csrf(login), StandardCharsets.UTF_8);
        var authenticated =
                client.send(
                        HttpRequest.newBuilder(URI.create(url("/admin/login")))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(form))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(authenticated.statusCode()).isEqualTo(302);
        var settings =
                client.send(
                        HttpRequest.newBuilder(URI.create(url("/admin/hotel"))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(settings.statusCode()).isEqualTo(200);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("_csrf", csrf(settings.body()));
        values.put("name", "Multipart Hotel");
        values.put("address", "12 Test Street");
        values.put("hotline", "0901000000");
        values.put("email", "info@example.test");
        values.put("description", "HTTP multipart regression");
        values.put("logoUrl", "");
        values.put("standardCheckIn", "14:00");
        values.put("standardCheckOut", "12:00");
        values.put("bookingHoldMinutes", "15");
        values.put("defaultTaxPercent", "10");
        values.put(
                "defaultDepositPolicyId",
                jdbc.queryForObject(
                                "SELECT default_deposit_policy_id FROM hotel_settings WHERE id=1",
                                Long.class)
                        .toString());
        values.put(
                "defaultCancellationPolicyId",
                jdbc.queryForObject(
                                "SELECT default_cancellation_policy_id FROM hotel_settings WHERE"
                                    + " id=1",
                                Long.class)
                        .toString());
        String boundary = "HotelPricingHttpBoundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var entry : values.entrySet())
            body.write(
                    ("--"
                                    + boundary
                                    + "\r\nContent-Disposition: form-data; name=\""
                                    + entry.getKey()
                                    + "\"\r\n\r\n"
                                    + entry.getValue()
                                    + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
        body.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"logo\";"
                                + " filename=\"logo.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", body);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response =
                client.send(
                        HttpRequest.newBuilder(URI.create(url("/admin/hotel")))
                                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT default_tax_percent FROM hotel_settings WHERE id=1",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("10");
        String logo =
                jdbc.queryForObject("SELECT logo_url FROM hotel_settings WHERE id=1", String.class);
        assertThat(logo).startsWith("/media/hotel/");
        Files.deleteIfExists(media.root().resolve(logo.substring(logo.lastIndexOf('/') + 1)));
        jdbc.update("UPDATE hotel_settings SET logo_url=NULL,name='Hotel'");
        CmsTestData.clear(jdbc);
    }
}
