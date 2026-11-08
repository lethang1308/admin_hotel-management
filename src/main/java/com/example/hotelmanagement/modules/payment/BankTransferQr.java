package com.example.hotelmanagement.modules.payment;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.hotel.service.HotelInventoryCoordinator;
import com.example.hotelmanagement.modules.reception.QrCodeSupport;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankTransferQr {
    private final OperationsStore db;
    private final HotelInventoryCoordinator inventory;
    private final QrCodeSupport qr;

    public record Settings(String bin, String account, String name, int revision) {}

    public record Code(
            Settings bank, String memo, BigDecimal amount, String payload, String image) {}

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund','hotel.manage')")
    public Settings settings() {
        var r = db.settings();
        return new Settings(
                r.s("bank_bin"),
                r.s("bank_account_number"),
                r.s("bank_account_name"),
                r.n("bank_revision"));
    }

    @PreAuthorize("hasAuthority('hotel.manage')")
    @Transactional
    public void save(Settings input) {
        inventory.lock();
        var old = db.settings();
        db.require(old.n("bank_revision") == input.revision(), "stale");
        String bin = db.optional(input.bin(), 6),
                account = db.optional(input.account(), 19),
                name = db.optional(input.name(), 50);
        db.require(
                (bin == null && account == null && name == null)
                        || (bin != null
                                && bin.matches("[0-9]{6}")
                                && account != null
                                && account.matches("[0-9]{6,19}")
                                && name != null),
                "bankConfig");
        db.update(
                "UPDATE hotel_settings SET"
                    + " bank_bin=?,bank_account_number=?,bank_account_name=?,bank_revision=bank_revision+1"
                    + " WHERE id=1",
                bin,
                account,
                name);
        db.audit(
                "BANK_SETTINGS_SAVE",
                "HOTEL",
                1,
                Map.of("revision", old.n("bank_revision")),
                Map.of("configured", bin != null),
                null);
    }

    @PreAuthorize("hasAuthority('payment.collect') and hasAuthority('booking.read.any')")
    public Code code(long booking, BigDecimal amount) {
        var bank = settings();
        db.require(bank.bin() != null && bank.account() != null, "bankConfig");
        var b = db.one("SELECT booking_code FROM bookings WHERE id=?", booking);
        db.require(
                amount != null
                        && amount.signum() > 0
                        && amount.compareTo(vnd(amount)) == 0
                        && amount.precision() - amount.scale() <= 13,
                "amount");
        String code = b.s("booking_code"), memo = code.substring(0, Math.min(25, code.length()));
        String payload =
                payload(bank.bin(), bank.account(), amount.toBigIntegerExact().toString(), memo);
        return new Code(bank, memo, amount, payload, qr.image(payload));
    }

    // NAPAS IBFT-to-account profile, generated locally; never implies payment success.
    public static String payload(String bin, String account, String amount, String memo) {
        String receiver = tlv("00", bin) + tlv("01", account);
        String consumer = tlv("00", "A000000727") + tlv("01", receiver) + tlv("02", "QRIBFTTA");
        String value =
                "000201010212"
                        + tlv("38", consumer)
                        + "5303704"
                        + tlv("54", amount)
                        + "5802VN"
                        + tlv("62", tlv("08", memo))
                        + "6304";
        return value + crc(value);
    }

    private static String tlv(String id, String value) {
        if (value == null
                || value.length() > 99
                || !StandardCharsets.US_ASCII.newEncoder().canEncode(value))
            throw new IllegalArgumentException("Invalid QR field");
        return id + String.format(Locale.ROOT, "%02d", value.length()) + value;
    }

    public static String crc(String text) {
        int crc = 0xffff;
        for (byte b : text.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 255) << 8;
            for (int i = 0; i < 8; i++)
                crc = ((crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xffff;
        }
        return String.format(Locale.ROOT, "%04X", crc);
    }
}
