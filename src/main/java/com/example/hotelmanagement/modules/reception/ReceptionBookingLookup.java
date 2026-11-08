package com.example.hotelmanagement.modules.reception;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.customer.service.IdentityDocumentCrypto;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
@PreAuthorize("hasAuthority('booking.read.any')")
public class ReceptionBookingLookup {
    private final OperationsStore db;
    private final IdentityDocumentCrypto documents;
    private final QrCodeSupport qr;

    public record Room(
            long id, String number, String type, String status, LocalDate from, LocalDate until) {}

    public record Match(
            long id, String code, String name, String phone, String status, List<Room> rooms) {}

    public record Issued(String payload, String image, Instant expiresAt) {}

    public List<Match> find(String query, String mode) {
        query = db.required(query, 150);
        db.require(query.length() >= 3, "input");
        db.require(mode != null && Set.of("CONTACT", "DOCUMENT", "QR").contains(mode), "input");
        List<Row> rows;
        if (mode.equals("QR") || query.startsWith("HOTEL-BOOKING:")) {
            require("booking.qr.scan");
            String token = query.startsWith("HOTEL-BOOKING:") ? query.substring(14) : query;
            db.require(token.matches("[A-Za-z0-9_-]{43}"), "qr");
            var tokens =
                    db.rows(
                            "SELECT * FROM booking_qr_tokens WHERE token_hash=? FOR UPDATE",
                            QrCodeSupport.hash(token));
            db.require(tokens.size() == 1, "qr");
            var t = tokens.get(0);
            db.require(
                    t.time("revoked_at") == null && Instant.now().isBefore(t.time("expires_at")),
                    "qr");
            db.update(
                    "UPDATE booking_qr_tokens SET last_scanned_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    t.id("id"));
            rows = db.rows("SELECT * FROM bookings WHERE id=?", t.id("booking_id"));
            db.audit("BOOKING_QR_SCAN", "BOOKING", t.id("booking_id"), Map.of(), Map.of(), null);
        } else if (mode.equals("DOCUMENT")) {
            require("customer.read");
            db.require(documents.configured(), "input");
            String hash = documents.hash(query);
            rows =
                    db.rows(
                            """
                SELECT b.* FROM bookings b JOIN customers c ON c.id=b.customer_id
                WHERE c.document_number_hash=? OR EXISTS(SELECT 1 FROM booking_guests g WHERE g.booking_id=b.id AND g.document_number_hash=?)
                ORDER BY b.id DESC LIMIT 20
                """,
                            hash,
                            hash);
        } else {
            String text = like(query), phone = like(query.replaceAll("[\\s().-]", ""));
            rows =
                    db.rows(
                            """
                SELECT b.* FROM bookings b JOIN customers c ON c.id=b.customer_id WHERE
                LOWER(b.booking_code) LIKE ? ESCAPE '!' OR LOWER(b.contact_name) LIKE ? ESCAPE '!'
                OR LOWER(c.full_name) LIKE ? ESCAPE '!' OR LOWER(b.contact_email) LIKE ? ESCAPE '!'
                OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(b.contact_phone,' ',''),'-',''),'.',''),'(',''),')','') LIKE ? ESCAPE '!'
                ORDER BY b.id DESC LIMIT 20
                """,
                            text,
                            text,
                            text,
                            text,
                            phone);
        }
        return rows.stream()
                .map(
                        b ->
                                new Match(
                                        b.id("id"),
                                        b.s("booking_code"),
                                        b.s("contact_name"),
                                        b.s("contact_phone"),
                                        b.s("status"),
                                        db
                                                .rows(
                                                        """
            SELECT br.id,br.room_type_name_snapshot,br.status,br.check_in_date,br.check_out_date,r.room_number
            FROM booking_rooms br LEFT JOIN room_allocations a ON a.id=(SELECT MAX(x.id) FROM room_allocations x WHERE x.booking_room_id=br.id)
            LEFT JOIN rooms r ON r.id=a.room_id WHERE br.booking_id=? ORDER BY br.line_number
            """,
                                                        b.id("id"))
                                                .stream()
                                                .map(
                                                        r ->
                                                                new Room(
                                                                        r.id("id"),
                                                                        r.s("room_number"),
                                                                        r.s(
                                                                                "room_type_name_snapshot"),
                                                                        r.s("status"),
                                                                        r.date("check_in_date"),
                                                                        r.date("check_out_date")))
                                                .toList()))
                .toList();
    }

    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.qr.scan')")
    public Issued issue(long booking) {
        var b = db.lockBooking(booking);
        db.require(
                db.count(
                                "SELECT COUNT(*) FROM booking_rooms WHERE booking_id=? AND status"
                                        + " IN ('PENDING','CONFIRMED','CHECKED_IN')",
                                booking)
                        > 0,
                "state");
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(Duration.ofHours(24));
        db.update(
                "UPDATE booking_qr_tokens SET revoked_at=CURRENT_TIMESTAMP(6) WHERE booking_id=?"
                        + " AND revoked_at IS NULL",
                booking);
        db.insert(
                "booking_qr_tokens",
                "booking_id,token_hash,expires_at",
                booking,
                QrCodeSupport.hash(token),
                ts(expires));
        db.audit(
                "BOOKING_QR_ISSUE",
                "BOOKING",
                booking,
                Map.of(),
                Map.of("expiresAt", expires),
                null);
        String payload = "HOTEL-BOOKING:" + token;
        return new Issued(payload, qr.image(payload), expires);
    }

    @PreAuthorize("hasAuthority('booking.read.any') and hasAuthority('booking.qr.scan')")
    public List<Match> image(byte[] data) {
        String payload = null;
        try {
            payload = qr.read(data);
        } catch (IllegalArgumentException e) {
            db.require(false, "qr");
        }
        db.require(payload != null && payload.startsWith("HOTEL-BOOKING:"), "qr");
        return find(payload, "QR");
    }

    private void require(String authority) {
        if (SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals(authority)))
            throw new AccessDeniedException("Missing permission");
    }
}
