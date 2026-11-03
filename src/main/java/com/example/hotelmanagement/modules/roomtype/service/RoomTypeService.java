package com.example.hotelmanagement.modules.roomtype.service;

import com.example.hotelmanagement.common.util.*;
import com.example.hotelmanagement.modules.amenity.dto.AmenityResponse;
import com.example.hotelmanagement.modules.amenity.repository.AmenityRepository;
import com.example.hotelmanagement.modules.audit.service.AuditLogService;
import com.example.hotelmanagement.modules.hotel.service.*;
import com.example.hotelmanagement.modules.roomtype.dto.*;
import com.example.hotelmanagement.modules.roomtype.entity.*;
import com.example.hotelmanagement.modules.roomtype.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('room.manage')")
public class RoomTypeService {
    private final RoomTypeRepository repository;
    private final RoomTypeImageRepository images;
    private final AmenityRepository amenities;
    private final HotelInventoryCoordinator guard;
    private final HotelMediaStorage media;
    private final CmsMessages messages;
    private final AuditLogService audit;

    @PreAuthorize("hasAnyAuthority('room.read','room.manage','room.block.manage')")
    public List<RoomTypeResponse> list() {
        return repository.findAll(org.springframework.data.domain.Sort.by("name")).stream()
                .map(this::view)
                .toList();
    }

    public RoomTypeForm form(Long id) {
        var e = required(id);
        var f = new RoomTypeForm();
        BeanUtils.copyProperties(e, f);
        f.setAmenityIds(
                new LinkedHashSet<>(e.getAmenities().stream().map(a -> a.getId()).toList()));
        return f;
    }

    @Transactional
    public Long save(Long id, RoomTypeForm f) {
        guard.lock();
        var e = id == null ? new RoomType() : required(id);
        var before = id == null ? null : HotelSnapshots.of(view(e));
        String code = f.getCode().trim().toUpperCase(Locale.ROOT);
        if (id == null
                ? repository.existsByCodeIgnoreCase(code)
                : repository.existsByCodeIgnoreCaseAndIdNot(code, id))
            throw messages.error("hotel.error.duplicateCode");
        if (f.getMaxOccupancy() < f.getMaxAdults()
                || f.getMaxOccupancy() > f.getMaxAdults() + f.getMaxChildren())
            throw messages.error("hotel.error.occupancy");
        if (id != null) {
            if (!f.isActive()
                    && guard.count(
                                    "SELECT COUNT(*) FROM rooms WHERE room_type_id=? AND"
                                            + " status<>'INACTIVE'",
                                    id)
                            > 0) throw messages.error("hotel.error.activeRooms");
            if ((f.getMaxAdults() < e.getMaxAdults()
                            || f.getMaxChildren() < e.getMaxChildren()
                            || f.getMaxOccupancy() < e.getMaxOccupancy())
                    && guard.count(
                                    "SELECT COUNT(*) FROM booking_rooms WHERE room_type_id=? AND"
                                            + " check_out_date>? AND status NOT IN"
                                            + " ('CANCELLED','CHECKED_OUT','NO_SHOW')",
                                    id,
                                    guard.today())
                            > 0) throw messages.error("hotel.error.futureBooking");
        }
        var chosen = amenities.findAllById(f.getAmenityIds());
        if (chosen.size() != f.getAmenityIds().size()) throw messages.error("hotel.error.notFound");
        for (var a : chosen)
            if (!a.isActive()
                    && e.getAmenities().stream().noneMatch(old -> old.getId().equals(a.getId())))
                throw messages.error("hotel.error.inactiveReference");
        BeanUtils.copyProperties(f, e);
        e.setCode(code);
        e.setName(f.getName().trim());
        e.setAmenities(new LinkedHashSet<>(chosen));
        repository.saveAndFlush(e);
        audit.record(
                "ROOM_TYPE_SAVE", "ROOM_TYPE", e.getId(), before, HotelSnapshots.of(view(e)), null);
        return e.getId();
    }

    @Transactional
    public void delete(Long id) {
        guard.lock();
        var e = required(id);
        if (guard.count("SELECT COUNT(*) FROM rooms WHERE room_type_id=?", id) > 0
                || guard.count("SELECT COUNT(*) FROM booking_rooms WHERE room_type_id=?", id) > 0
                || guard.count(
                                "SELECT COUNT(*) FROM booking_room_nights WHERE"
                                        + " inventory_room_type_id=?",
                                id)
                        > 0
                || guard.count("SELECT COUNT(*) FROM rate_rule_room_types WHERE room_type_id=?", id)
                        > 0) throw messages.error("hotel.error.inUse");
        if (guard.count("SELECT COUNT(*) FROM surcharge_policies WHERE room_type_id=?", id) > 0
                || guard.count("SELECT COUNT(*) FROM voucher_room_types WHERE room_type_id=?", id)
                        > 0
                || guard.count("SELECT COUNT(*) FROM favorites WHERE room_type_id=?", id) > 0)
            throw messages.error("hotel.error.inUse");
        var before = HotelSnapshots.of(view(e));
        images.deleteAll(images.findByRoomType_IdOrderBySortOrderAscIdAsc(id));
        images.flush();
        guardJdbcDeleteInventory(id);
        repository.delete(e);
        repository.flush();
        audit.record("ROOM_TYPE_DELETE", "ROOM_TYPE", id, before, null, null);
    }

    private final JdbcTemplate jdbc;

    private void guardJdbcDeleteInventory(Long id) {
        jdbc.update("DELETE FROM room_type_inventory WHERE room_type_id=?", id);
    }

    public List<RoomTypeImageResponse> images(Long id) {
        required(id);
        return images.findByRoomType_IdOrderBySortOrderAscIdAsc(id).stream()
                .map(this::imageView)
                .toList();
    }

    @Transactional
    public void addImages(Long id, String urls, String altText, List<MultipartFile> files) {
        guard.lock();
        var type = required(id);
        List<String> additions = new ArrayList<>();
        if (urls != null)
            for (String url : urls.split("\\R")) {
                if (!url.isBlank()) additions.add(media.validateUrl(url));
            }
        if (files != null && files.stream().filter(f -> !f.isEmpty()).count() > 8)
            throw messages.error("hotel.error.imageLimit");
        var old = images.findByRoomType_IdOrderBySortOrderAscIdAsc(id);
        int uploadCount =
                files == null ? 0 : (int) files.stream().filter(f -> !f.isEmpty()).count();
        if (additions.size() + uploadCount + old.size() > 50 || additions.size() + uploadCount == 0)
            throw messages.error("hotel.error.imageLimit");
        if (altText != null && altText.length() > 200)
            throw messages.error("hotel.error.imageFile");
        if (files != null)
            for (var file : files) if (!file.isEmpty()) additions.add(media.store(file));
        int order = old.size();
        for (String url : additions)
            images.save(
                    RoomTypeImage.builder()
                            .roomType(type)
                            .imageUrl(url)
                            .altText(altText)
                            .sortOrder(order++)
                            .build());
        images.flush();
        audit.record("ROOM_IMAGES_ADD", "ROOM_TYPE", id, null, Map.of("urls", additions), null);
    }

    @Transactional
    public void moveImage(Long id, Long imageId, int position) {
        guard.lock();
        required(id);
        var all = images.findByRoomType_IdOrderBySortOrderAscIdAsc(id);
        var target =
                all.stream()
                        .filter(e -> e.getId().equals(imageId))
                        .findFirst()
                        .orElseThrow(() -> messages.error("hotel.error.notFound"));
        all.remove(target);
        all.add(Math.max(0, Math.min(position, all.size())), target);
        normalize(all);
        audit.record(
                "ROOM_IMAGE_REORDER",
                "ROOM_TYPE",
                id,
                null,
                Map.of("imageId", imageId, "position", position),
                null);
    }

    @Transactional
    public void deleteImage(Long id, Long imageId) {
        guard.lock();
        required(id);
        var image =
                images.findByIdAndRoomType_Id(imageId, id)
                        .orElseThrow(() -> messages.error("hotel.error.notFound"));
        var before = HotelSnapshots.of(imageView(image));
        images.delete(image);
        images.flush();
        normalize(images.findByRoomType_IdOrderBySortOrderAscIdAsc(id));
        audit.record("ROOM_IMAGE_DELETE", "ROOM_TYPE", id, before, null, null);
    }

    private void normalize(List<RoomTypeImage> all) {
        for (int i = 0; i < all.size(); i++) all.get(i).setSortOrder(100000 + i);
        images.flush();
        for (int i = 0; i < all.size(); i++) all.get(i).setSortOrder(i);
        images.flush();
    }

    private RoomType required(Long id) {
        return repository.findById(id).orElseThrow(() -> messages.error("hotel.error.notFound"));
    }

    private RoomTypeImageResponse imageView(RoomTypeImage e) {
        return new RoomTypeImageResponse(
                e.getId(), e.getImageUrl(), e.getAltText(), e.getSortOrder());
    }

    private RoomTypeResponse view(RoomType e) {
        var gallery = images.findByRoomType_IdOrderBySortOrderAscIdAsc(e.getId());
        return new RoomTypeResponse(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getDescription(),
                e.getBasePrice(),
                e.getAreaSqm(),
                e.getMaxAdults(),
                e.getMaxChildren(),
                e.getMaxOccupancy(),
                e.getBedDescription(),
                e.isFeatured(),
                e.isActive(),
                gallery.isEmpty() ? null : gallery.get(0).getImageUrl(),
                e.getAmenities().stream()
                        .map(
                                a ->
                                        new AmenityResponse(
                                                a.getId(),
                                                a.getCode(),
                                                a.getName(),
                                                a.getIcon(),
                                                a.isActive()))
                        .toList());
    }
}
