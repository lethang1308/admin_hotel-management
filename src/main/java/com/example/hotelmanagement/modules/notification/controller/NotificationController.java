package com.example.hotelmanagement.modules.notification.controller;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.notification.service.CustomerNotificationService;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final CustomerNotificationService notifications;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CustomerNotificationService.Notice>>> list(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(notifications.list(user.getId(), page, size)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unread(
            @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(Map.of("count", notifications.unread(user.getId()))));
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<Void> read(
            @AuthenticationPrincipal CustomUserDetails user, @PathVariable long id) {
        notifications.read(user.getId(), id);
        return ApiResponse.okMessage("Đã đánh dấu đã đọc");
    }

    @PatchMapping("/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal CustomUserDetails user) {
        notifications.readAll(user.getId());
        return ApiResponse.okMessage("Đã đánh dấu tất cả đã đọc");
    }
}
