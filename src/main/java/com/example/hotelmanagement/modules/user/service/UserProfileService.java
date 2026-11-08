package com.example.hotelmanagement.modules.user.service;

import com.example.hotelmanagement.common.exception.*;
import com.example.hotelmanagement.common.util.PhoneNumbers;
import com.example.hotelmanagement.modules.audit.service.*;
import com.example.hotelmanagement.modules.customer.repository.CustomerRepository;
import com.example.hotelmanagement.modules.user.dto.*;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('profile.manage.own')")
public class UserProfileService {
    private final UserRepository users;
    private final CustomerRepository customers;
    private final ProfileProvisioningService provisioning;
    private final AuditLogService audit;
    private final AvatarStorage avatars;

    public UserProfileResponse get(long actor) {
        return view(required(actor));
    }

    @Transactional
    public UserProfileResponse update(long actor, UpdateProfileRequest f) {
        User u = locked(actor);
        String phone = PhoneNumbers.normalize(f.phone());
        if (users.existsByPhoneAndIdNot(phone, actor)
                || users.existsByPhoneAndIdNot("+84" + phone.substring(1), actor))
            throw new DuplicateResourceException("Số điện thoại đã được sử dụng");
        var before = AuditSnapshots.user(u);
        u.setFullName(f.fullName().strip());
        u.setPhone(phone);
        provisioning.sync(u);
        var customer = customers.findByUser_Id(actor);
        if (customer.isEmpty()
                && (f.dateOfBirth() != null
                        || f.gender() != null
                        || f.address() != null && !f.address().isBlank()))
            throw new BusinessException("Thông tin này dành cho hồ sơ khách hàng.");
        customer.ifPresent(
                c -> {
                    c.setDateOfBirth(f.dateOfBirth());
                    c.setGender(f.gender());
                    c.setAddress(
                            f.address() == null || f.address().isBlank()
                                    ? null
                                    : f.address().strip());
                });
        users.flush();
        customers.flush();
        audit.record(
                "USER_PROFILE_UPDATE",
                "USER",
                actor,
                before,
                AuditSnapshots.user(u),
                "Self-service profile update");
        return view(u);
    }

    @Transactional
    public String avatar(long actor, org.springframework.web.multipart.MultipartFile file) {
        User u = locked(actor);
        String previous = u.getAvatarUrl(), url = avatars.save(file);
        u.setAvatarUrl(url);
        users.flush();
        audit.record(
                "USER_AVATAR_UPDATE",
                "USER",
                actor,
                Map.of("avatar", Objects.toString(previous, "")),
                Map.of("avatar", url),
                "Self-service avatar upload");
        // Old public avatar files are retained to keep cached URLs valid.
        return url;
    }

    private User locked(long id) {
        return users.lockById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private User required(long id) {
        return users.findWithRolesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private UserProfileResponse view(User u) {
        var c = customers.findByUser_Id(u.getId()).orElse(null);
        return new UserProfileResponse(
                u.getId(),
                u.getFullName(),
                u.getEmail(),
                u.getPhone(),
                u.getAvatarUrl(),
                c == null ? null : c.getDateOfBirth(),
                c == null ? null : c.getGender(),
                c == null ? null : c.getAddress(),
                c == null ? null : c.getNationalityCode(),
                c == null ? null : c.getCustomerCode(),
                u.getRoles().stream()
                        .map(x -> x.getCode())
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
