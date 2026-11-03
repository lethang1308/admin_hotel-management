package com.example.hotelmanagement.modules.voucher.dto;

public record VoucherView(
        Long id,
        VoucherForm form,
        boolean used,
        long reserved,
        long redeemed,
        long released,
        Long remaining) {
    public long getUsagePercent() {
        return form.getMaxUses() == null
                ? 0
                : Math.min(100, (reserved + redeemed) * 100 / form.getMaxUses());
    }
}
