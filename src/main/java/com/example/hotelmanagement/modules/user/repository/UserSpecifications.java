package com.example.hotelmanagement.modules.user.repository;

import com.example.hotelmanagement.common.pagination.PaginationUtils;
import com.example.hotelmanagement.modules.role.entity.Role;
import com.example.hotelmanagement.modules.user.entity.User;
import com.example.hotelmanagement.modules.user.entity.UserStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> withFilters(String keyword, UserStatus status, String roleCode) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(keyword)) {
                String escaped = PaginationUtils.escapeLikePattern(keyword.toLowerCase(Locale.ROOT));
                String pattern = "%" + escaped + "%";
                Predicate nameMatch = cb.like(cb.lower(root.get("fullName")), pattern, '\\');
                Predicate emailMatch = cb.like(cb.lower(root.get("email")), pattern, '\\');
                predicates.add(cb.or(nameMatch, emailMatch));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (StringUtils.hasText(roleCode)) {
                if (query != null) {
                    query.distinct(true);
                }
                Join<User, Role> roleJoin = root.join("roles", JoinType.INNER);
                predicates.add(cb.equal(cb.upper(roleJoin.get("code")), roleCode.trim().toUpperCase(Locale.ROOT)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
