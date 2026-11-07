package com.example.hotelmanagement.modules.reception;

import com.example.hotelmanagement.modules.booking.*;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.payment.FrontDeskPaymentService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReceptionActionService {
    private final HotelInventoryCoordinator inventory;
    private final OperationsStore db;
    private final StayService stays;
    private final FrontDeskPaymentService payments;

    @PreAuthorize("hasAuthority('booking.checkin')")
    public void checkIn(
            long booking,
            long line,
            List<StayService.Guest> guests,
            String reason,
            String key,
            String method,
            BigDecimal amount) {
        inventory.lock();
        var order = db.lockBooking(booking);
        if ("CHECKED_IN".equals(db.bookingRoom(booking, line).s("status"))) return;
        db.require(amount != null && amount.signum() >= 0, "amount");
        if (amount.signum() > 0) payments.collect(booking, key, "PAYMENT", method, amount, reason);
        stays.checkIn(booking, line, guests, reason);
    }
}
