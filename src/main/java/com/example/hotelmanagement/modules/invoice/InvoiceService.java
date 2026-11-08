package com.example.hotelmanagement.modules.invoice;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
@PreAuthorize("hasAuthority('invoice.manage')")
public class InvoiceService {
    private final OperationsStore db;
    private final com.example.hotelmanagement.modules.reception.QrCodeSupport qr;

    @Value("${app.invoice.directory:uploads/invoices-private}")
    private String directory;

    public record Seller(String name, String address, String hotline, String email) {}

    public record Download(String name, byte[] data) {}

    public long issue(long booking, String buyerAddress, String taxCode) {
        var order = db.lockBooking(booking);
        var existing =
                db.rows(
                        "SELECT * FROM invoices WHERE booking_id=? ORDER BY revision DESC",
                        booking);
        if (!existing.isEmpty() && existing.get(0).s("status").equals("ISSUED"))
            return existing.get(0).id("id");
        db.require(
                existing.stream()
                        .noneMatch(
                                i ->
                                        i.s("status").equals("DRAFT")
                                                || i.s("status").equals("ISSUED")),
                "invoiced");
        db.require(
                db.count(
                                "SELECT COUNT(*) FROM booking_rooms WHERE booking_id=? AND status"
                                        + " IN ('PENDING','CONFIRMED','CHECKED_IN')",
                                booking)
                        == 0,
                "invoiceState");
        var charges =
                db.rows(
                        """
                SELECT * FROM booking_charges WHERE booking_id=? AND status='POSTED' ORDER BY service_date,id
                """,
                        booking);
        db.require(!charges.isEmpty(), "invoiceState");
        var hotel = db.settings();
        var seller =
                new Seller(
                        hotel.s("name"), hotel.s("address"), hotel.s("hotline"), hotel.s("email"));
        BigDecimal subtotal = ZERO, tax = ZERO;
        for (var charge : charges) {
            if (charge.s("kind").equals("TAX")) tax = tax.add(charge.money("amount"));
            else subtotal = subtotal.add(charge.money("amount"));
        }
        BigDecimal total = subtotal.add(tax);
        db.require(
                total.signum() >= 0
                        && subtotal.abs().precision() <= 15
                        && tax.abs().precision() <= 15
                        && total.abs().precision() <= 15,
                "amount");
        Row previous = existing.isEmpty() ? null : existing.get(0);
        int revision = previous == null ? 1 : previous.n("revision") + 1;
        Instant now = Instant.now();
        long id =
                db.insert(
                        "invoices",
                        "invoice_number,booking_id,revision,supersedes_invoice_id,status,seller_snapshot,buyer_name,buyer_email,"
                            + "buyer_address,buyer_tax_code,currency,subtotal_amount,tax_amount,total_amount,issued_by,issued_at",
                        code("HD"),
                        booking,
                        revision,
                        previous == null ? null : previous.id("id"),
                        "ISSUED",
                        db.json(seller),
                        order.s("contact_name"),
                        order.s("contact_email"),
                        db.optional(buyerAddress, 500),
                        db.optional(taxCode, 30),
                        order.s("currency"),
                        subtotal,
                        tax,
                        total,
                        db.actor(),
                        ts(now));
        int number = 0;
        for (var charge : charges)
            db.insert(
                    "invoice_lines",
                    "invoice_id,booking_id,booking_charge_id,line_number,kind,description,service_date,quantity,unit_price,amount",
                    id,
                    booking,
                    charge.id("id"),
                    ++number,
                    charge.s("kind"),
                    charge.s("description"),
                    charge.date("service_date"),
                    charge.money("quantity"),
                    charge.money("unit_price"),
                    charge.money("amount"));
        var invoice = db.one("SELECT * FROM invoices WHERE id=?", id);
        String key = invoice.s("invoice_number") + ".pdf";
        Path path = path(key);
        byte[] pdf = render(invoice, order, seller, charges);
        store(path, pdf);
        db.update("UPDATE invoices SET pdf_storage_key=? WHERE id=?", key, id);
        db.audit(
                "INVOICE_ISSUE",
                "INVOICE",
                id,
                Map.of(),
                Map.of("bookingId", booking, "revision", revision, "total", total),
                null);
        return id;
    }

    public void voidInvoice(long id, String reason) {
        var invoice = db.one("SELECT booking_id FROM invoices WHERE id=?", id);
        db.lockBooking(invoice.id("booking_id"));
        var current = db.one("SELECT status FROM invoices WHERE id=? FOR UPDATE", id);
        if (current.s("status").equals("VOID")) return;
        reason = db.required(reason, 1000);
        db.update(
                "UPDATE invoices SET"
                        + " status='VOID',void_reason=?,voided_at=CURRENT_TIMESTAMP(6),voided_by=?"
                        + " WHERE id=?",
                reason,
                db.actor(),
                id);
        db.audit(
                "INVOICE_VOID",
                "INVOICE",
                id,
                Map.of("status", current.s("status")),
                Map.of("status", "VOID"),
                reason);
    }

    @Transactional(readOnly = true)
    public Download download(long id) {
        var invoice = db.one("SELECT invoice_number,pdf_storage_key FROM invoices WHERE id=?", id);
        db.require(invoice.s("pdf_storage_key") != null, "missing");
        try {
            return new Download(
                    invoice.s("invoice_number") + ".pdf",
                    Files.readAllBytes(path(invoice.s("pdf_storage_key"))));
        } catch (IOException error) {
            db.require(false, "pdf");
            throw new IllegalStateException(error);
        }
    }

    private Path path(String key) {
        db.require(key.matches("HD[A-F0-9]{24}\\.pdf"), "pdf");
        return Path.of(directory).toAbsolutePath().normalize().resolve(key);
    }

    private void store(Path path, byte[] pdf) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, pdf, StandardOpenOption.CREATE_NEW);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    /* The UUID key is private and never reused. */
                                }
                            }
                        }
                    });
        } catch (IOException error) {
            db.require(false, "pdf");
        }
    }

    private byte[] render(Row invoice, Row order, Seller seller, List<Row> charges) {
        var rows = new StringBuilder();
        for (var charge : charges)
            rows.append("<tr><td>")
                    .append(escape(charge.s("service_date")))
                    .append("</td><td>")
                    .append(escape(charge.s("description")))
                    .append("</td><td class='num'>")
                    .append(number(charge, "quantity"))
                    .append("</td><td class='num'>")
                    .append(number(charge, "unit_price"))
                    .append("</td><td class='num'>")
                    .append(number(charge, "amount"))
                    .append("</td></tr>");
        String html =
                """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="UTF-8" />
                <style>
                @page { size:A4; margin:18mm; }
                body { font-family:Noto Sans; font-size:10pt; color:#16243a; }
                h1 { color:#194b86; font-size:22pt; margin:6pt 0; }
                p { margin:4pt 0; }
                table { width:100%%; border-collapse:collapse; margin:14pt 0; -fs-table-paginate:paginate; }
                th { text-align:left; background:#e8f0f9; }
                td, th { border-bottom:1px solid #cfd7e1; padding:6px; }
                tr { page-break-inside:avoid; }
                .num { text-align:right; white-space:nowrap; }
                .muted { color:#43546a; }
                </style></head><body>
                <p><b>%s</b></p><p>%s</p><p>%s %s</p>
                <h1>Hóa đơn nội bộ</h1>
                <p class="muted">Internal invoice · %s · Phiên bản %d · %s</p>
                <p>Mã đặt phòng: %s</p>
                <p>Khách hàng: <b>%s</b> %s</p><p>%s %s</p>
                <table><thead><tr><th>Ngày</th><th>Diễn giải</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr></thead>
                <tbody>%s</tbody></table>
                <p class="num">Trước thuế: %s VND</p><p class="num">Thuế: %s VND</p>
                <p class="num"><b>Tổng hóa đơn: %s VND</b></p>
                <p class="muted">Chứng từ nội bộ. Thông tin thu tiền và công nợ được theo dõi riêng trong CMS.</p>
                </body></html>
                """
                        .formatted(
                                escape(seller.name()),
                                escape(seller.address()),
                                escape(seller.hotline()),
                                escape(seller.email()),
                                escape(invoice.s("invoice_number")),
                                invoice.n("revision"),
                                invoice.time("issued_at").atZone(db.zone()).toLocalDate(),
                                escape(order.s("booking_code")),
                                escape(invoice.s("buyer_name")),
                                escape(invoice.s("buyer_email")),
                                escape(invoice.s("buyer_address")),
                                escape(invoice.s("buyer_tax_code")),
                                rows,
                                number(invoice, "subtotal_amount"),
                                number(invoice, "tax_amount"),
                                number(invoice, "total_amount"));
        String footer =
                "<table style='margin-top:24pt'><tr><td style='width:30%;border:0'><img"
                        + " style='width:85pt;height:85pt' src='"
                        + qr.image("HOTEL-INVOICE:" + invoice.s("invoice_number"))
                        + "' /><p style='font-size:8pt'>QR định danh hóa đơn nội bộ</p></td><td"
                        + " style='width:35%;border:0;text-align:center;vertical-align:top'><b>Khách"
                        + " hàng</b><p>(Ký, ghi rõ họ tên)</p></td><td"
                        + " style='width:35%;border:0;text-align:center;vertical-align:top'><b>Đại"
                        + " diện khách sạn</b><p>(Ký, ghi rõ họ tên)</p></td></tr></table>";
        html = html.replace("</body>", footer + "</body>");
        try (var regular = new ClassPathResource("fonts/NotoSans-Regular.ttf").getInputStream();
                var bold = new ClassPathResource("fonts/NotoSans-Bold.ttf").getInputStream();
                var output = new ByteArrayOutputStream()) {
            var builder = new PdfRendererBuilder();
            builder.useFont(
                    () -> regular, "Noto Sans", 400, PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.useFont(
                    () -> bold, "Noto Sans", 700, PdfRendererBuilder.FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null).toStream(output).run();
            return output.toByteArray();
        } catch (IOException | RuntimeException error) {
            db.require(false, "pdf");
            throw new IllegalStateException(error);
        }
    }

    private String escape(String value) {
        return Objects.toString(value, "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String number(Row row, String key) {
        return row.money(key).stripTrailingZeros().toPlainString();
    }
}
