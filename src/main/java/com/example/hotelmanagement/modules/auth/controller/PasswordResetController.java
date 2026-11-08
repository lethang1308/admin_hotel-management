package com.example.hotelmanagement.modules.auth.controller;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.modules.auth.dto.PasswordResetRequests.*;
import com.example.hotelmanagement.modules.auth.service.PasswordResetService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class PasswordResetController {
    private final PasswordResetService passwords;

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgot(@Valid @RequestBody Forgot f) {
        passwords.request(f.email());
        return ApiResponse.okMessage(
                "Nếu tài khoản đủ điều kiện tồn tại, hướng dẫn đặt lại mật khẩu sẽ được gửi qua"
                    + " email.");
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> reset(@Valid @RequestBody Reset f) {
        passwords.reset(f.token(), f.newPassword());
        return ApiResponse.okMessage("Đã đặt lại mật khẩu. Vui lòng đăng nhập lại.");
    }
}
