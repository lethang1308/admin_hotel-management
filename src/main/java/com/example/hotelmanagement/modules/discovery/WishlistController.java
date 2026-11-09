package com.example.hotelmanagement.modules.discovery;

import com.example.hotelmanagement.common.api.ApiResponse;
import com.example.hotelmanagement.common.pagination.PageResponse;
import com.example.hotelmanagement.security.authentication.CustomUserDetails;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/wishlist")
public class WishlistController {
    private final WishlistService wishlist;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DiscoveryModels.RoomCard>>> list(
            @AuthenticationPrincipal CustomUserDetails actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(wishlist.list(actor.getId(), page, size)));
    }

    @GetMapping("/ids")
    public ResponseEntity<ApiResponse<List<Long>>> ids(
            @AuthenticationPrincipal CustomUserDetails actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(wishlist.ids(actor.getId())));
    }

    @PostMapping("/{id}")
    public ApiResponse<Void> add(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable long id) {
        wishlist.add(actor.getId(), id);
        return ApiResponse.okMessage("Đã thêm vào danh sách yêu thích");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(
            @AuthenticationPrincipal CustomUserDetails actor, @PathVariable long id) {
        wishlist.remove(actor.getId(), id);
        return ApiResponse.okMessage("Đã xóa khỏi danh sách yêu thích");
    }
}
