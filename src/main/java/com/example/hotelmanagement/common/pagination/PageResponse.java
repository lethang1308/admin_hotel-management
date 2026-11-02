package com.example.hotelmanagement.common.pagination;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

@Getter
@Builder
@Schema(description = "Standard paginated response metadata and items")
public class PageResponse<T> {

    @Schema(description = "Items on the current page")
    private final List<T> items;

    @Schema(description = "Zero-based current page index", example = "0")
    private final int page;

    @Schema(description = "Requested page size", example = "10")
    private final int size;

    @Schema(description = "Total number of matching elements", example = "42")
    private final long totalElements;

    @Schema(description = "Total number of pages", example = "5")
    private final int totalPages;

    @Schema(description = "Whether this is the first page", example = "true")
    private final boolean first;

    @Schema(description = "Whether this is the last page", example = "false")
    private final boolean last;

    @Schema(description = "Whether there is a next page", example = "true")
    private final boolean hasNext;

    @Schema(description = "Whether there is a previous page", example = "false")
    private final boolean hasPrevious;

    public static <E, D> PageResponse<D> from(Page<E> pageData, Function<E, D> mapper) {
        List<D> mappedItems = pageData.getContent().stream().map(mapper).toList();
        return PageResponse.<D>builder()
                .items(mappedItems)
                .page(pageData.getNumber())
                .size(pageData.getSize())
                .totalElements(pageData.getTotalElements())
                .totalPages(pageData.getTotalPages())
                .first(pageData.isFirst())
                .last(pageData.isLast())
                .hasNext(pageData.hasNext())
                .hasPrevious(pageData.hasPrevious())
                .build();
    }

    public static <T> PageResponse<T> from(Page<T> pageData) {
        return from(pageData, Function.identity());
    }
}
