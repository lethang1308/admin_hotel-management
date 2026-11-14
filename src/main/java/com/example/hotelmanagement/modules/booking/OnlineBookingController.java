package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OnlineBookingService.*;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OnlineBookingController {
    private final OnlineBookingService service;
    private final ObjectMapper mapper;

    public record Voucher(long bookingId, String voucherCode) {}

    public record Cancel(String reason) {}

    @PostMapping("/bookings/quote")
    public ApiResponse<?> quote(@RequestBody Cart input) {
        return ApiResponse.ok(service.quote(input));
    }

    @PostMapping("/bookings/init")
    public ApiResponse<?> init(@RequestBody Cart input) {
        return ApiResponse.ok(service.init(input));
    }

    @PostMapping("/bookings/{id}/addGuests")
    public ApiResponse<?> guests(@PathVariable long id, @RequestBody JsonNode input) {
        GuestDetails details;
        try {
            if (input.isArray()) {
                if (input.size() > 100)
                    throw new BusinessException("Danh sách khách vượt giới hạn.");
                var ids = new ArrayList<Long>();
                var guests = new ArrayList<GuestInput>();
                for (var guest : input) {
                    if (guest.isIntegralNumber() && guest.canConvertToLong())
                        ids.add(guest.asLong());
                    else if (guest.isObject())
                        guests.add(mapper.treeToValue(guest, GuestInput.class));
                    else throw new BusinessException("Thông tin khách không hợp lệ.");
                }
                details = new GuestDetails(null, null, null, null, ids, guests, null);
            } else {
                details = mapper.treeToValue(input, GuestDetails.class);
                if (details == null) throw new BusinessException("Thông tin khách không hợp lệ.");
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw new BusinessException("Thông tin khách không hợp lệ.");
        }
        return ApiResponse.ok(service.saveGuests(id, details));
    }

    @PostMapping("/vouchers/apply")
    public ApiResponse<?> voucher(@RequestBody Voucher input) {
        return ApiResponse.ok(service.voucher(input.bookingId(), input.voucherCode()));
    }

    @GetMapping("/bookings/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.details(id));
    }

    @GetMapping("/bookings/{id}/status")
    public ApiResponse<?> status(@PathVariable long id) {
        return ApiResponse.ok(service.status(id));
    }

    @PostMapping("/bookings/{id}/payments")
    public ApiResponse<?> begin(@PathVariable long id, @RequestBody PaymentRequest input) {
        return ApiResponse.ok(service.begin(id, input));
    }

    @PostMapping("/payments/sandbox/process")
    public ApiResponse<?> process(@RequestBody PaymentAction input) {
        return ApiResponse.ok(service.process(input));
    }

    @PostMapping("/bookings/{id}/cancel")
    public ApiResponse<?> cancel(@PathVariable long id, @RequestBody Cancel input) {
        return ApiResponse.ok(service.cancel(id, input.reason()));
    }

    @GetMapping("/users/myBookings")
    public ApiResponse<?> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(service.mine(page, size, status, sort));
    }

    @GetMapping("/users/guests")
    public ApiResponse<?> guests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.guests(page, size));
    }

    @PostMapping("/users/guests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createGuest(@RequestBody GuestInput input) {
        return ApiResponse.ok(service.saveGuest(null, input));
    }

    @PutMapping("/users/guests/{id}")
    public ApiResponse<?> updateGuest(@PathVariable long id, @RequestBody GuestInput input) {
        return ApiResponse.ok(service.saveGuest(id, input));
    }

    @DeleteMapping("/users/guests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGuest(@PathVariable long id) {
        service.deleteGuest(id);
    }
}
