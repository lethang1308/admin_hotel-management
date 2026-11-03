package com.example.hotelmanagement.modules.user.controller;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.modules.user.dto.CreateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserRequest;
import com.example.hotelmanagement.modules.user.dto.UpdateUserStatusRequest;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import com.example.hotelmanagement.modules.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('account.manage')")
@RequiredArgsConstructor
@Tag(
        name = "User Management",
        description = "Administrative endpoints for managing user accounts and roles")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "List users with pagination, search, filtering, and sorting")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Users retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Unauthorized",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Forbidden (requires ADMIN role)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getUsers(
            @Parameter(description = "Zero-based page index", example = "0")
                    @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(description = "Page size (1..100)", example = "10")
                    @RequestParam(defaultValue = "10")
                    int size,
            @Parameter(
                            description = "Sort criteria in format field,asc|desc",
                            example = "createdAt,desc")
                    @RequestParam(defaultValue = "createdAt,desc")
                    String sort,
            @Parameter(description = "Optional keyword matching fullName or email")
                    @RequestParam(required = false)
                    String search,
            @Parameter(description = "Optional status filter") @RequestParam(required = false)
                    UserStatus status,
            @Parameter(description = "Optional role code filter (e.g. ADMIN, USER)")
                    @RequestParam(required = false)
                    String role) {
        PageResponse<UserResponse> result =
                userService.getUsers(page, size, sort, search, status, role);
        return ResponseEntity.ok(ApiResponse.ok("Users retrieved successfully", result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user details by ID")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "User found"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "User not found",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.ok("User retrieved successfully", userService.getUserById(id)));
    }

    @PostMapping
    @Operation(summary = "Create a new user account")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "User created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation error",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Duplicate email",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse created = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("User created successfully", created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user profile and assigned roles")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request,
            Authentication authentication) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        UserResponse updated = userService.updateUser(id, request, actorEmail);
        return ResponseEntity.ok(ApiResponse.ok("User updated successfully", updated));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate, deactivate, or lock a user account")
    public ResponseEntity<ApiResponse<UserResponse>> updateUserStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserStatusRequest request,
            Authentication authentication) {
        String actorEmail = authentication != null ? authentication.getName() : null;
        UserResponse updated = userService.updateUserStatus(id, request, actorEmail);
        return ResponseEntity.ok(ApiResponse.ok("User status updated successfully", updated));
    }
}
