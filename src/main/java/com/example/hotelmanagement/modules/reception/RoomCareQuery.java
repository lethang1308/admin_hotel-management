package com.example.hotelmanagement.modules.reception;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RoomCareQuery {
    private final OperationsStore db;

    @PreAuthorize("hasAuthority('booking.read.any')")
    @Transactional(readOnly = true)
    public Map<Long, OperationsStore.Row> occupied() {
        var result = new HashMap<Long, OperationsStore.Row>();
        for (var r :
                db.rows(
                        """
   SELECT a.room_id,b.id AS booking_id,b.booking_code,b.contact_name,b.customer_id,br.id AS line_id,
   COALESCE((SELECT SUM(c.amount) FROM booking_charges c WHERE c.booking_id=b.id AND c.status='POSTED'),0)
   -COALESCE((SELECT SUM(CASE WHEN p.kind='REFUND' THEN -p.amount ELSE p.amount END) FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCEEDED'),0) AS debt
   FROM room_allocations a JOIN booking_rooms br ON br.id=a.booking_room_id JOIN bookings b ON b.id=br.booking_id WHERE a.status='OCCUPIED' AND a.occupied_until_at IS NULL
   """))
            result.put(r.id("room_id"), r);
        return result;
    }
}
