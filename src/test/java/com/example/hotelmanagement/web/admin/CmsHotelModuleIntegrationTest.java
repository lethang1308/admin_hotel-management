package com.example.hotelmanagement.web.admin;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.hotelmanagement.common.exception.AppException;
import com.example.hotelmanagement.modules.amenity.dto.*;
import com.example.hotelmanagement.modules.amenity.service.*;
import com.example.hotelmanagement.modules.floor.dto.*;
import com.example.hotelmanagement.modules.floor.service.*;
import com.example.hotelmanagement.modules.hotel.dto.*;
import com.example.hotelmanagement.modules.hotel.service.*;
import com.example.hotelmanagement.modules.room.dto.*;
import com.example.hotelmanagement.modules.room.entity.*;
import com.example.hotelmanagement.modules.room.service.*;
import com.example.hotelmanagement.modules.roomtype.dto.*;
import com.example.hotelmanagement.modules.roomtype.service.*;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.service.UserService;
import com.example.hotelmanagement.support.CmsTestData;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import javax.imageio.ImageIO;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CmsHotelModuleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired HotelSettingService hotel;
    @Autowired HotelInventoryCoordinator inventory;
    @Autowired HotelMediaStorage media;
    @Autowired FloorService floors;
    @Autowired AmenityService amenities;
    @Autowired RoomTypeService types;
    @Autowired RoomService rooms;
    @Autowired RoomBlockService blocks;
    @Autowired RoomQueryService query;
    @Autowired RoomCalendarService calendar;
    Long adminId, floorId, typeId;
    MockHttpSession admin;

    @BeforeEach
    void setup() throws Exception {
        CmsTestData.clear(jdbc);
        adminId =
                users.createUser(
                                CreateUserRequest.builder()
                                        .fullName("Hotel Admin")
                                        .email("hotel.admin@example.test")
                                        .password("InitialPass123")
                                        .roles(Set.of("ADMIN"))
                                        .build())
                        .getId();
        admin = login("hotel.admin@example.test");
        SecurityContextHolder.setContext(
                (SecurityContext) admin.getAttribute("SPRING_SECURITY_CONTEXT"));
        var floor = new FloorForm();
        floor.setCode("TEST_F1");
        floor.setName("Tầng 1");
        floor.setAreaName("Main wing");
        floorId = floors.save(null, floor);
        var type = new RoomTypeForm();
        type.setCode("TEST_DLX");
        type.setName("Deluxe Test");
        type.setBasePrice(new BigDecimal("850000"));
        type.setMaxChildren(1);
        type.setMaxOccupancy(3);
        typeId = types.save(null, type);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/admin/login")
                                        .with(csrf())
                                        .param("email", email)
                                        .param("password", "InitialPass123"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    private Long room(String number) {
        var f = new RoomForm();
        f.setRoomNumber(number);
        f.setFloorId(floorId);
        f.setRoomTypeId(typeId);
        f.setSellFrom(inventory.today());
        return rooms.save(null, f);
    }

    private RoomStatusChangeForm change(Long id, RoomStatus status) {
        var f = new RoomStatusChangeForm();
        f.setStatus(status);
        f.setReason("Operational test");
        f.setVersion(query.get(id).version());
        return f;
    }

    private RoomBlockForm block(Long id, LocalDate from, LocalDate to) {
        var f = new RoomBlockForm();
        f.setRoomId(id);
        f.setFromDate(from);
        f.setToDate(to);
        f.setReason("Repair air conditioning");
        return f;
    }

    private int value(String column, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT "
                        + column
                        + " FROM room_type_inventory WHERE room_type_id=? AND stay_date=?",
                Integer.class,
                typeId,
                date);
    }

    @Test
    void allPagesRenderInVietnameseAndEnglish() throws Exception {
        Long id = room("101");
        for (String lang : List.of("vi", "en"))
            for (String path :
                    List.of(
                            "/admin/hotel",
                            "/admin/floors",
                            "/admin/floors/create",
                            "/admin/floors/" + floorId + "/edit",
                            "/admin/amenities",
                            "/admin/amenities/create",
                            "/admin/room-types",
                            "/admin/room-types/create",
                            "/admin/room-types/" + typeId + "/edit",
                            "/admin/rooms",
                            "/admin/rooms/create",
                            "/admin/rooms/" + id + "/edit",
                            "/admin/rooms/" + id,
                            "/admin/rooms/grid",
                            "/admin/room-blocks"))
                mvc.perform(get(path).param("lang", lang).session(admin))
                        .andExpect(status().isOk())
                        .andExpect(
                                content()
                                        .string(
                                                org.hamcrest.Matchers.not(
                                                        containsString("??hotel."))));
    }

    @Test
    void catalogueGuardsAndImagesPreserveCoverOrder() throws Exception {
        Long id = room("102");
        assertThatThrownBy(() -> floors.delete(floorId)).isInstanceOf(AppException.class);
        var f = floors.form(floorId);
        f.setActive(false);
        assertThatThrownBy(() -> floors.save(floorId, f)).isInstanceOf(AppException.class);
        var a = new AmenityForm();
        a.setCode("TEST_WIFI");
        a.setName("Test WiFi");
        Long aid = amenities.save(null, a);
        var type = types.form(typeId);
        type.setAmenityIds(Set.of(aid));
        types.save(typeId, type);
        assertThatThrownBy(() -> amenities.delete(aid)).isInstanceOf(AppException.class);
        types.addImages(
                typeId, "https://example.test/a.jpg\nhttps://example.test/b.jpg", "Room", null);
        var images = types.images(typeId);
        assertThat(images).hasSize(2);
        types.moveImage(typeId, images.get(1).id(), 0);
        assertThat(types.images(typeId).get(0).imageUrl()).endsWith("b.jpg");
        types.deleteImage(typeId, images.get(0).id());
        assertThat(types.images(typeId).get(0).sortOrder()).isZero();
        assertThatThrownBy(() -> types.delete(typeId)).isInstanceOf(AppException.class);
        rooms.delete(id);
        assertThat(query.all(null, null, null, null, null)).isEmpty();
    }

    @Test
    void uploadsAreDecodedAndUnsafeUrlsAreRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        var file = new MockMultipartFile("files", "room.png", "image/png", out.toByteArray());
        types.addImages(typeId, "", "Room", List.of(file));
        String url = types.images(typeId).get(0).imageUrl();
        assertThat(url).startsWith("/media/hotel/");
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
        SecurityContextHolder.setContext(
                (SecurityContext) admin.getAttribute("SPRING_SECURITY_CONTEXT"));
        java.nio.file.Files.deleteIfExists(
                media.root().resolve(url.substring(url.lastIndexOf('/') + 1)));
        assertThatThrownBy(() -> types.addImages(typeId, "javascript:alert(1)", "", null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(
                        () ->
                                types.addImages(
                                        typeId,
                                        "",
                                        "",
                                        List.of(
                                                new MockMultipartFile(
                                                        "files",
                                                        "x.svg",
                                                        "image/svg+xml",
                                                        "<svg/>".getBytes()))))
                .isInstanceOf(AppException.class);
    }

    @Test
    void statusCycleWritesActorHistoryAndAuditAndRejectsStaleUpdates() {
        Long id = room("103");
        var stale = change(id, RoomStatus.CLEANING);
        rooms.changeStatus(id, change(id, RoomStatus.OCCUPIED));
        assertThatThrownBy(() -> rooms.changeStatus(id, stale)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rooms.changeStatus(id, change(id, RoomStatus.AVAILABLE)))
                .isInstanceOf(AppException.class);
        rooms.changeStatus(id, change(id, RoomStatus.DIRTY));
        rooms.changeStatus(id, change(id, RoomStatus.CLEANING));
        rooms.changeStatus(id, change(id, RoomStatus.AVAILABLE));
        assertThat(query.history(id)).hasSize(5);
        assertThat(query.history(id).get(0).actorName()).isEqualTo("Hotel Admin");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_logs WHERE action='ROOM_STATUS_CHANGE'"
                                        + " AND entity_id=?",
                                Long.class,
                                id))
                .isEqualTo(4);
    }

    @Test
    void currentMaintenanceClaimsEveryNightAndReleaseRestoresCapacity() {
        Long id = room("104");
        LocalDate d = inventory.today();
        Long bid = blocks.create(block(id, d, d.plusDays(3)));
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.MAINTENANCE);
        assertThat(value("blocked_rooms", d)).isEqualTo(1);
        assertThat(value("sellable_rooms", d)).isZero();
        assertThat(query.isAvailable(id, d, d.plusDays(1))).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_block_id=?",
                                Integer.class,
                                bid))
                .isEqualTo(3);
        assertThatThrownBy(() -> rooms.changeStatus(id, change(id, RoomStatus.AVAILABLE)))
                .isInstanceOf(AppException.class);
        blocks.release(bid, "Repair complete");
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.CLEANING);
        assertThat(value("blocked_rooms", d)).isZero();
        rooms.changeStatus(id, change(id, RoomStatus.AVAILABLE));
        assertThat(query.isAvailable(id, d, d.plusDays(1))).isTrue();
        assertThatThrownBy(() -> blocks.release(bid, "Again")).isInstanceOf(AppException.class);
    }

    @Test
    void futureBlocksDoNotChangeCurrentStatusAndAdjacentDatesAreAllowed() {
        Long id = room("105");
        LocalDate d = inventory.today();
        Long b = blocks.create(block(id, d.plusDays(2), d.plusDays(4)));
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(query.isAvailable(id, d, d.plusDays(2))).isTrue();
        assertThat(query.isAvailable(id, d.plusDays(3), d.plusDays(5))).isFalse();
        assertThatThrownBy(() -> blocks.create(block(id, d.plusDays(3), d.plusDays(6))))
                .isInstanceOf(AppException.class);
        blocks.create(block(id, d.plusDays(4), d.plusDays(5)));
        blocks.release(b, "Cancel future repair");
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(value("blocked_rooms", d.plusDays(2))).isZero();
    }

    @Test
    void unassignedReservationsPreventOverbookingAndRollbackBlockAtomically() {
        Long id = room("106");
        LocalDate d = inventory.today();
        jdbc.update(
                "UPDATE room_type_inventory SET reserved_rooms=1 WHERE room_type_id=? AND"
                        + " stay_date=?",
                typeId,
                d);
        assertThatThrownBy(() -> blocks.create(block(id, d, d.plusDays(1))))
                .isInstanceOf(AppException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_blocks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_night_claims", Integer.class))
                .isZero();
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(value("reserved_rooms", d)).isEqualTo(1);
        assertThat(query.history(id)).hasSize(1);
        assertThatThrownBy(() -> rooms.retire(id, "Close")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rooms.delete(id)).isInstanceOf(AppException.class);
        assertThat(query.get(id)).isNotNull();
    }

    @Test
    void roomChangesRespectBoundsDuplicateNumbersAndInactiveReferences() {
        Long id = room("107");
        assertThatThrownBy(() -> room("107")).isInstanceOf(AppException.class);
        var f = rooms.form(id);
        f.setSellUntil(f.getSellFrom());
        assertThatThrownBy(() -> rooms.save(id, f)).isInstanceOf(AppException.class);
        var updated = rooms.form(id);
        updated.setSellUntil(updated.getSellFrom().plusDays(3));
        rooms.save(id, updated);
        assertThatThrownBy(
                        () ->
                                blocks.create(
                                        block(
                                                id,
                                                inventory.today().plusDays(2),
                                                inventory.today().plusDays(4))))
                .isInstanceOf(AppException.class);
        rooms.retire(id, "Renovation");
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.INACTIVE);
        assertThat(value("total_rooms", inventory.today())).isZero();
        rooms.changeStatus(id, change(id, RoomStatus.CLEANING));
        assertThat(value("total_rooms", inventory.today())).isEqualTo(1);
    }

    @Test
    void bookingAllocationIsVisibleInHistoryAndPreventsUnsafeOperations() {
        Long id = room("108");
        Long booking = allocation(id, true);
        assertThat(query.stays(id)).hasSize(1);
        assertThat(query.get(id).guestName()).isEqualTo("Guest History");
        assertThat(query.grid(floorId, "Main wing", typeId, null).total()).isEqualTo(1);
        assertThat(query.isAvailable(id, inventory.today(), inventory.today().plusDays(1)))
                .isFalse();
        assertThatThrownBy(
                        () ->
                                blocks.create(
                                        block(
                                                id,
                                                inventory.today(),
                                                inventory.today().plusDays(2))))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rooms.changeStatus(id, change(id, RoomStatus.CLEANING)))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rooms.retire(id, "Close")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> rooms.delete(id)).isInstanceOf(AppException.class);
    }

    @Test
    void scheduledAllocationWithoutNightClaimsStillPreventsMaintenance() {
        Long id = room("109");
        allocation(id, false);
        assertThatThrownBy(
                        () ->
                                blocks.create(
                                        block(
                                                id,
                                                inventory.today(),
                                                inventory.today().plusDays(2))))
                .isInstanceOf(AppException.class);
        var f = rooms.form(id);
        f.setRoomTypeId(
                jdbc.queryForObject("SELECT id FROM room_types WHERE code='STD'", Long.class));
        assertThatThrownBy(() -> rooms.save(id, f)).isInstanceOf(AppException.class);
    }

    @Test
    void hotelSettingsUpdateAndBrandAreAudited() {
        var f = hotel.form();
        f.setName("Khách sạn Minh An");
        f.setAddress("12 Nguyễn Huệ");
        f.setHotline("0901234567");
        f.setStandardCheckIn(LocalTime.of(15, 0));
        hotel.update(f, null);
        assertThat(hotel.get().name()).isEqualTo("Khách sạn Minh An");
        assertThat(hotel.brand().name()).isEqualTo(f.getName());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT updated_by FROM hotel_settings WHERE id=1", Long.class))
                .isEqualTo(adminId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM audit_logs WHERE action='HOTEL_UPDATE'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void gridFiltersCountsAndRoomModalRender() throws Exception {
        Long id = room("110");
        Long second = room("111");
        rooms.changeStatus(second, change(second, RoomStatus.CLEANING));
        assertThat(query.grid(floorId, null, typeId, RoomStatus.CLEANING).counts().get("CLEANING"))
                .isEqualTo(1L);
        assertThat(query.search(99, 1, null, null, null, null, "11").getItems()).hasSize(1);
        mvc.perform(get("/admin/rooms/grid").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("roomQuickModal")))
                .andExpect(content().string(containsString("data-room-id=\"" + id + "\"")))
                .andExpect(content().string(containsString("state-CLEANING")));
    }

    @Test
    void receptionPermissionsAndCsrfAreEnforced() throws Exception {
        Long id = room("112");
        users.createUser(
                CreateUserRequest.builder()
                        .fullName("Desk")
                        .email("hotel.desk@example.test")
                        .password("InitialPass123")
                        .roles(Set.of("RECEPTIONIST"))
                        .build());
        var desk = login("hotel.desk@example.test");
        for (String path :
                List.of(
                        "/admin/hotel",
                        "/admin/floors",
                        "/admin/amenities",
                        "/admin/room-types",
                        "/admin/rooms/create",
                        "/admin/room-blocks"))
            mvc.perform(get(path).session(desk)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/rooms/grid").session(desk)).andExpect(status().isOk());
        mvc.perform(
                        post("/admin/rooms/" + id + "/status")
                                .session(desk)
                                .with(csrf())
                                .param("status", "MAINTENANCE")
                                .param("version", "0")
                                .param("reason", "Unauthorized")
                                .param(
                                        "maintenanceUntil",
                                        inventory.today().plusDays(1).toString()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_blocks", Integer.class)).isZero();
        mvc.perform(
                        post("/admin/rooms/" + id + "/status")
                                .session(admin)
                                .param("status", "CLEANING")
                                .param("version", "0")
                                .param("reason", "test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPostedFormsReturnLocalizedErrorsAndDoNotPersist() throws Exception {
        mvc.perform(
                        post("/admin/floors")
                                .session(admin)
                                .with(csrf())
                                .param("code", "BAD CODE")
                                .param("name", "")
                                .param("areaName", "")
                                .param("floorNumber", "999")
                                .param("sortOrder", "-1")
                                .param("lang", "vi"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bắt buộc")));
        SecurityContextHolder.setContext(
                (SecurityContext) admin.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertThat(floors.list()).hasSize(1);
        mvc.perform(get("/admin/rooms").session(admin).param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calendarEndsExpiredBlockAndPreservesHistoricalClaims() {
        Long id = room("113");
        LocalDate d = inventory.today();
        Long bid = blocks.create(block(id, d, d.plusDays(1)));
        jdbc.update("DELETE FROM room_night_claims WHERE room_block_id=?", bid);
        jdbc.update(
                "UPDATE room_blocks SET from_date=?,to_date=? WHERE id=?", d.minusDays(2), d, bid);
        jdbc.update(
                "INSERT INTO room_night_claims(room_id,stay_date,room_block_id) VALUES(?,?,?)",
                id,
                d.minusDays(1),
                bid);
        calendar.synchronize();
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.CLEANING);
        assertThat(query.blocks(id).get(0).status()).isEqualTo("RELEASED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM room_night_claims WHERE room_block_id=?",
                                Integer.class,
                                bid))
                .isEqualTo(1);
    }

    @Test
    void concurrentBlocksCannotConsumeAlreadyReservedCapacity() throws Exception {
        Long first = room("114"), second = room("115");
        LocalDate d = inventory.today();
        jdbc.update(
                "UPDATE room_type_inventory SET reserved_rooms=1 WHERE room_type_id=? AND"
                        + " stay_date=?",
                typeId,
                d);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (Long id : List.of(first, second))
                futures.add(
                        pool.submit(
                                () -> {
                                    var context = SecurityContextHolder.createEmptyContext();
                                    context.setAuthentication(authentication);
                                    SecurityContextHolder.setContext(context);
                                    try {
                                        gate.await();
                                        blocks.create(block(id, d, d.plusDays(1)));
                                        return true;
                                    } catch (AppException ex) {
                                        return false;
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            gate.countDown();
            int successes = 0;
            for (var f : futures) if (f.get(20, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(value("blocked_rooms", d)).isEqualTo(1);
            assertThat(value("reserved_rooms", d)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_blocks", Integer.class))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidBlockDatesAndRetiringFutureClaimAreRejected() {
        Long id = room("116");
        LocalDate d = inventory.today();
        for (var f :
                List.of(
                        block(id, d.minusDays(1), d.plusDays(1)),
                        block(id, d, d),
                        block(id, d, inventory.horizon().plusDays(1))))
            assertThatThrownBy(() -> blocks.create(f)).isInstanceOf(AppException.class);
        blocks.create(block(id, d.plusDays(2), d.plusDays(4)));
        assertThatThrownBy(() -> rooms.retire(id, "Close")).isInstanceOf(AppException.class);
        var form = rooms.form(id);
        form.setSellUntil(d.plusDays(3));
        assertThatThrownBy(() -> rooms.save(id, form)).isInstanceOf(AppException.class);
        assertThat(value("total_rooms", d)).isEqualTo(1);
    }

    @Test
    void schedulerActivatesDueBlocksAndRetiresEndedRooms() {
        Long id = room("117"), ended = room("118");
        LocalDate d = inventory.today();
        Long bid = blocks.create(block(id, d.plusDays(1), d.plusDays(3)));
        jdbc.update(
                "INSERT INTO room_night_claims(room_id,stay_date,room_block_id) VALUES(?,?,?)",
                id,
                d,
                bid);
        jdbc.update("UPDATE room_blocks SET from_date=? WHERE id=?", d, bid);
        jdbc.update(
                "UPDATE rooms SET sell_from=?,sell_until=? WHERE id=?", d.minusDays(2), d, ended);
        calendar.synchronize();
        assertThat(query.get(id).status()).isEqualTo(RoomStatus.MAINTENANCE);
        assertThat(query.get(ended).status()).isEqualTo(RoomStatus.INACTIVE);
        assertThat(value("total_rooms", d)).isEqualTo(1);
        assertThat(value("blocked_rooms", d)).isEqualTo(1);
    }

    @Test
    void receptionistCannotReactivateAnAdministrativelyRetiredRoom() throws Exception {
        Long id = room("119");
        rooms.retire(id, "Closed by admin");
        users.createUser(
                CreateUserRequest.builder()
                        .fullName("Desk")
                        .email("hotel.desk@example.test")
                        .password("InitialPass123")
                        .roles(Set.of("RECEPTIONIST"))
                        .build());
        var desk = login("hotel.desk@example.test");
        mvc.perform(
                        post("/admin/rooms/" + id + "/status")
                                .session(desk)
                                .with(csrf())
                                .param("status", "CLEANING")
                                .param(
                                        "version",
                                        jdbc.queryForObject(
                                                        "SELECT version FROM rooms WHERE id=?",
                                                        Integer.class,
                                                        id)
                                                .toString())
                                .param("reason", "Reopen"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM rooms WHERE id=?", String.class, id))
                .isEqualTo("INACTIVE");
    }

    private Long allocation(Long roomId, boolean occupied) {
        Long uid =
                users.createUser(
                                CreateUserRequest.builder()
                                        .fullName("Guest History")
                                        .email("history@example.test")
                                        .password("InitialPass123")
                                        .roles(Set.of("USER"))
                                        .build())
                        .getId();
        Long customerId =
                jdbc.queryForObject("SELECT id FROM customers WHERE user_id=?", Long.class, uid);
        return allocationWithCustomer(roomId, occupied, customerId);
    }

    private Long allocationWithCustomer(Long roomId, boolean occupied, Long customerId) {
        LocalDate d = inventory.today();
        var h = hotel.get();
        jdbc.update(
                "INSERT INTO"
                    + " bookings(booking_code,customer_id,source,status,contact_name,contact_phone,deposit_policy_id,cancellation_policy_id,policy_snapshot)"
                    + " VALUES('HISTORY_TEST',?,'WALK_IN','CONFIRMED','Guest"
                    + " History','0901000000',?,?,'{}')",
                customerId,
                h.defaultDepositPolicyId(),
                h.defaultCancellationPolicyId());
        Long bid =
                jdbc.queryForObject(
                        "SELECT id FROM bookings WHERE booking_code='HISTORY_TEST'", Long.class);
        jdbc.update(
                "INSERT INTO"
                    + " booking_rooms(booking_id,line_number,room_type_id,room_type_name_snapshot,check_in_date,check_out_date,expected_check_in_at,expected_check_out_at,adults,checked_in_by)"
                    + " VALUES(?,1,?,'Deluxe',?,?,?, ?,2,?)",
                bid,
                typeId,
                d,
                d.plusDays(2),
                java.sql.Timestamp.valueOf(d.atTime(14, 0)),
                java.sql.Timestamp.valueOf(d.plusDays(2).atTime(12, 0)),
                adminId);
        Long br =
                jdbc.queryForObject(
                        "SELECT id FROM booking_rooms WHERE booking_id=?", Long.class, bid);
        jdbc.update(
                "INSERT INTO"
                    + " room_allocations(booking_room_id,room_id,from_date,to_date,status,occupied_from_at)"
                    + " VALUES(?,?,?,?,?,?)",
                br,
                roomId,
                d,
                d.plusDays(2),
                occupied ? "OCCUPIED" : "SCHEDULED",
                occupied ? java.sql.Timestamp.from(Instant.now()) : null);
        return bid;
    }
}
