package com.example.hotelmanagement.modules.voucher.repository;

import com.example.hotelmanagement.modules.voucher.entity.Voucher;

import org.springframework.data.jpa.repository.*;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {
    boolean existsByCodeIgnoreCase(String code);

    java.util.Optional<Voucher> findByCodeIgnoreCase(String code);
}
