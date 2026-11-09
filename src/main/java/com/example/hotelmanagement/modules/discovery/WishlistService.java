package com.example.hotelmanagement.modules.discovery;

import com.example.hotelmanagement.common.exception.ResourceNotFoundException;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('favorite.manage.own')")
@Transactional(readOnly = true)
public class WishlistService {
    private final OperationsStore db;
    private final RoomDiscoveryService discovery;

    public PageResponse<DiscoveryModels.RoomCard> list(long user, int page, int size) {
        return discovery.favorites(customer(user, false), page, size);
    }

    public List<Long> ids(long user) {
        return db
                .rows(
                        """
                SELECT f.room_type_id FROM favorites f JOIN room_types t ON t.id=f.room_type_id
                WHERE f.customer_id=? AND t.is_active=TRUE ORDER BY f.room_type_id
                """,
                        customer(user, false))
                .stream()
                .map(r -> r.id("room_type_id"))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void add(long user, long room) {
        long customer = customer(user, true);
        if (db.count("SELECT COUNT(*) FROM room_types WHERE id=? AND is_active=TRUE", room) == 0)
            throw new ResourceNotFoundException("Room type", room);
        if (db.count(
                        "SELECT COUNT(*) FROM favorites WHERE customer_id=? AND room_type_id=?",
                        customer,
                        room)
                == 0)
            db.update(
                    "INSERT INTO favorites(customer_id,room_type_id) VALUES(?,?)", customer, room);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(long user, long room) {
        db.update(
                "DELETE FROM favorites WHERE customer_id=? AND room_type_id=?",
                customer(user, true),
                room);
    }

    private long customer(long user, boolean lock) {
        return db
                .rows(
                        "SELECT id FROM customers WHERE user_id=?" + (lock ? " FOR UPDATE" : ""),
                        user)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile", user))
                .id("id");
    }
}
