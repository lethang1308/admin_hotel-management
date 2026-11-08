package com.example.hotelmanagement.modules.user.controller;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.modules.user.dto.*;
import com.example.hotelmanagement.modules.user.service.UserProfileService;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/profile")
public class UserProfileController {
    private final UserProfileService profiles;

    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> get(
            @AuthenticationPrincipal CustomUserDetails actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(profiles.get(actor.getId())));
    }

    @PutMapping
    public ApiResponse<UserProfileResponse> update(
            @AuthenticationPrincipal CustomUserDetails actor,
            @Valid @RequestBody UpdateProfileRequest form) {
        return ApiResponse.ok("Đã cập nhật hồ sơ", profiles.update(actor.getId(), form));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> avatar(
            @AuthenticationPrincipal CustomUserDetails actor, @RequestParam MultipartFile file) {
        return ApiResponse.ok(Map.of("avatarUrl", profiles.avatar(actor.getId(), file)));
    }
}
