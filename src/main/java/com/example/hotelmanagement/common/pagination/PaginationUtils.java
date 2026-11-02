package com.example.hotelmanagement.common.pagination;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.common.exception.ErrorCode;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

public final class PaginationUtils {

    private static final int MAX_SEARCH_LENGTH = 100;

    private PaginationUtils() {
    }

    public static Pageable createPageable(
            int page,
            int size,
            String sortParam,
            Set<String> allowedSortFields,
            String defaultSortField,
            Sort.Direction defaultDirection,
            int maxPageSize
    ) {
        if (page < 0) {
            throw new BusinessException(ErrorCode.INVALID_PAGINATION, "Page index must not be less than zero");
        }
        if (size < 1 || size > maxPageSize) {
            throw new BusinessException(
                    ErrorCode.INVALID_PAGINATION,
                    "Page size must be between 1 and " + maxPageSize
            );
        }

        Sort sort = parseAndValidateSort(sortParam, allowedSortFields, defaultSortField, defaultDirection);
        return PageRequest.of(page, size, sort);
    }

    public static Sort parseAndValidateSort(
            String sortParam,
            Set<String> allowedSortFields,
            String defaultSortField,
            Sort.Direction defaultDirection
    ) {
        if (!StringUtils.hasText(sortParam)) {
            return Sort.by(defaultDirection, defaultSortField);
        }

        String[] parts = sortParam.trim().split(",");
        if (parts.length == 0 || parts.length > 2) {
            throw new BusinessException(
                    ErrorCode.INVALID_SORT_PARAMETER,
                    "Invalid sort format. Expected 'field,asc' or 'field,desc'"
            );
        }

        String property = parts[0].trim();
        if (!allowedSortFields.contains(property)) {
            throw new BusinessException(
                    ErrorCode.INVALID_SORT_PARAMETER,
                    "Unsupported sort field '" + property + "'. Allowed fields: " + allowedSortFields
            );
        }

        Sort.Direction direction = defaultDirection;
        if (parts.length == 2 && StringUtils.hasText(parts[1])) {
            String dir = parts[1].trim();
            if ("asc".equalsIgnoreCase(dir)) {
                direction = Sort.Direction.ASC;
            } else if ("desc".equalsIgnoreCase(dir)) {
                direction = Sort.Direction.DESC;
            } else {
                throw new BusinessException(
                        ErrorCode.INVALID_SORT_PARAMETER,
                        "Unsupported sort direction '" + dir + "'. Allowed values: asc, desc"
                );
            }
        }

        return Sort.by(direction, property);
    }

    public static String normalizeSearchKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_SEARCH_LENGTH) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "Search keyword must not exceed " + MAX_SEARCH_LENGTH + " characters"
            );
        }
        return trimmed;
    }

    public static String escapeLikePattern(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
