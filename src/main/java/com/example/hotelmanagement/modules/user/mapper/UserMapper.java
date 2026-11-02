package com.example.hotelmanagement.modules.user.mapper;

import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.user.dto.UserResponse;
import com.example.hotelmanagement.modules.user.entity.User;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        Set<String> roleCodes = user.getRoles() == null
                ? Set.of()
                : user.getRoles().stream()
                        .map(Role::getCode)
                        .sorted()
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        return UserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .status(user.getStatus())
                .roles(roleCodes)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
