package com.example.hotelmanagement.modules.pricing.service;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize(
        "hasAnyAuthority('pricing.manage','policy.manage','voucher.manage','room.read','booking.create')")
public class PricingCatalogueService {
    private final JdbcTemplate jdbc;

    public record TypeOption(
            Long id, String code, String name, BigDecimal basePrice, boolean active) {}

    public List<TypeOption> types() {
        return jdbc.query(
                "SELECT id,code,name,base_price,is_active FROM room_types ORDER BY name,id",
                (r, n) ->
                        new TypeOption(
                                r.getLong(1),
                                r.getString(2),
                                r.getString(3),
                                r.getBigDecimal(4),
                                r.getBoolean(5)));
    }
}
