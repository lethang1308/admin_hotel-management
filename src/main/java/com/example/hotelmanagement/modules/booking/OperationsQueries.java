package com.example.hotelmanagement.modules.booking;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.BookingChangeService.Saved;
import com.example.hotelmanagement.modules.payment.BookingLedger;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationsQueries {
    private static final int SIZE = 25;
    private final OperationsStore db;
    private final BookingLedger ledger;

    public record Page(List<Row> rows, int index, long total) {
        public boolean previous() {
            return index > 0;
        }

        public boolean next() {
            return (long) (index + 1) * SIZE < total;
        }
    }

    public record Detail(
            Row booking,
            List<Row> rooms,
            List<Row> nights,
            List<Row> allocations,
            List<Row> guests,
            List<Row> charges,
            List<Row> payments,
            List<Row> invoices,
            List<Row> history,
            List<Row> cancellations,
            List<Row> usages,
            List<ChangeRow> changes,
            List<Row> audit,
            BookingLedger.Balance balance) {}

    public record ChangeRow(Row row, Saved requested) {}

    public record CalendarRow(Row room, List<Cell> cells) {}

    public record Cell(String state, String text, Long booking) {}

    public record Calendar(
            LocalDate from,
            LocalDate until,
            List<LocalDate> days,
            List<CalendarRow> rooms,
            List<Row> inventory) {}

    @PreAuthorize("hasAuthority('booking.read.any')")
    public Page bookings(
            String search,
            String status,
            String source,
            Long type,
            LocalDate from,
            LocalDate until,
            int page) {
        return bookings(search, status, source, type, from, until, page, "ALL");
    }

    @PreAuthorize("hasAuthority('booking.read.any')")
    public Map<String, Long> quickCounts() {
        var day = db.today();
        return Map.of(
                "ALL",
                db.count("SELECT COUNT(*) FROM bookings"),
                "ARRIVALS",
                db.count(
                        "SELECT COUNT(DISTINCT booking_id) FROM booking_rooms WHERE check_in_date=?"
                            + " AND status='CONFIRMED'",
                        day),
                "DEPARTURES",
                db.count(
                        "SELECT COUNT(DISTINCT booking_id) FROM booking_rooms WHERE"
                            + " check_out_date=? AND status='CHECKED_IN'",
                        day),
                "PENDING",
                db.count("SELECT COUNT(*) FROM bookings WHERE status='PENDING'"),
                "IN_HOUSE",
                db.count(
                        "SELECT COUNT(DISTINCT booking_id) FROM booking_rooms WHERE"
                            + " status='CHECKED_IN'"));
    }

    @PreAuthorize("hasAuthority('booking.read.any')")
    public Page bookings(
            String search,
            String status,
            String source,
            Long type,
            LocalDate from,
            LocalDate until,
            int page,
            String quick) {
        db.require(
                Set.of("ALL", "ARRIVALS", "DEPARTURES", "PENDING", "IN_HOUSE").contains(quick),
                "input");
        dates(from, until);
        var sql =
                new StringBuilder(
                        """
                FROM bookings b JOIN customers c ON c.id=b.customer_id WHERE 1=1
                """);
        var args = new ArrayList<Object>();
        if (search != null && !search.isBlank()) {
            db.require(search.length() <= 150, "input");
            sql.append(
                    """
                     AND (LOWER(b.booking_code) LIKE ? ESCAPE '!' OR LOWER(b.contact_name) LIKE ? ESCAPE '!'
                       OR LOWER(b.contact_phone) LIKE ? ESCAPE '!' OR LOWER(b.contact_email) LIKE ? ESCAPE '!'
                       OR LOWER(c.full_name) LIKE ? ESCAPE '!' OR LOWER(c.phone) LIKE ? ESCAPE '!'
                       OR LOWER(c.email) LIKE ? ESCAPE '!')
                    """);
            args.addAll(Collections.nCopies(7, like(search)));
            String phone = search.replaceAll("[\\s().-]", "");
            if (phone.matches("[+0-9]{3,20}")) {
                int end = sql.lastIndexOf(")");
                sql.insert(
                        end,
                        " OR REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(b.contact_phone,'"
                            + " ',''),'-',''),'.',''),'(',''),')','') LIKE ? ESCAPE '!' OR"
                            + " REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(c.phone,'"
                            + " ',''),'-',''),'.',''),'(',''),')','') LIKE ? ESCAPE '!'");
                args.add(like(phone));
                args.add(like(phone));
            }
        }
        add(sql, args, " AND b.status=?", status);
        add(sql, args, " AND b.source=?", source);
        if ("PENDING".equals(quick)) {
            sql.append(" AND b.status='PENDING'");
        }
        boolean lineFilter = Set.of("ARRIVALS", "DEPARTURES", "IN_HOUSE").contains(quick);
        if (type != null || from != null || until != null || lineFilter) {
            sql.append(" AND EXISTS(SELECT 1 FROM booking_rooms r WHERE r.booking_id=b.id");
            add(sql, args, " AND r.room_type_id=?", type);
            add(sql, args, " AND r.check_out_date>?", from);
            add(sql, args, " AND r.check_in_date<?", until);
            switch (quick) {
                case "ARRIVALS" -> {
                    sql.append(" AND r.check_in_date=? AND r.status='CONFIRMED'");
                    args.add(db.today());
                }
                case "DEPARTURES" -> {
                    sql.append(" AND r.check_out_date=? AND r.status='CHECKED_IN'");
                    args.add(db.today());
                }
                case "IN_HOUSE" -> sql.append(" AND r.status='CHECKED_IN'");
                default -> {}
            }
            sql.append(")");
        }
        return page(
                "SELECT b.*, (SELECT MIN(r.check_in_date) FROM booking_rooms r WHERE"
                    + " r.booking_id=b.id) AS arrival, (SELECT MAX(r.check_out_date) FROM"
                    + " booking_rooms r WHERE r.booking_id=b.id) AS departure, COALESCE((SELECT"
                    + " SUM(c.amount) FROM booking_charges c WHERE c.booking_id=b.id AND"
                    + " c.status='POSTED'),0) AS total_due, COALESCE((SELECT SUM(CASE WHEN"
                    + " p.kind='REFUND' THEN -p.amount ELSE p.amount END) FROM payment_transactions"
                    + " p WHERE p.booking_id=b.id AND p.status='SUCCEEDED'),0) AS net_paid ",
                sql,
                args,
                "b.id DESC",
                page);
    }

    @PreAuthorize("hasAuthority('booking.read.any')")
    public Detail detail(long id) {
        var booking = db.one("SELECT * FROM bookings WHERE id=?", id);
        var rooms =
                db.rows(
                        """
                SELECT br.*, r.room_number,r.status AS physical_status FROM booking_rooms br LEFT JOIN room_allocations a ON a.id=(
                  SELECT MAX(ra.id) FROM room_allocations ra WHERE ra.booking_room_id=br.id)
                LEFT JOIN rooms r ON r.id=a.room_id WHERE br.booking_id=? ORDER BY br.line_number
                """,
                        id);
        var nights =
                db.rows(
                        """
                SELECT n.*,br.line_number FROM booking_room_nights n JOIN booking_rooms br ON br.id=n.booking_room_id
                WHERE br.booking_id=? ORDER BY br.line_number,n.stay_date,n.revision
                """,
                        id);
        var assignments =
                db.rows(
                        """
                SELECT a.*,r.room_number,br.line_number FROM room_allocations a JOIN rooms r ON r.id=a.room_id
                JOIN booking_rooms br ON br.id=a.booking_room_id WHERE br.booking_id=? ORDER BY br.line_number,a.id
                """,
                        id);
        var guests =
                db.rows(
                        """
                SELECT sg.id,g.full_name,g.guest_type,g.document_type,br.line_number,sg.arrived_at,sg.departed_at
                FROM stay_guests sg JOIN booking_guests g ON g.id=sg.booking_guest_id
                JOIN booking_rooms br ON br.id=sg.booking_room_id WHERE sg.booking_id=? ORDER BY sg.id
                """,
                        id);
        var audit =
                db.rows(
                        """
                SELECT a.action,a.reason,a.created_at,u.email AS actor FROM audit_logs a
                LEFT JOIN users u ON u.id=a.actor_user_id WHERE entity_type='BOOKING' AND entity_id=? ORDER BY a.id DESC LIMIT 100
                """,
                        Long.toString(id));
        return new Detail(
                booking,
                rooms,
                nights,
                assignments,
                guests,
                children("booking_charges", id),
                children("payment_transactions", id),
                children("invoices", id),
                children("booking_status_history", id),
                children("booking_cancellations", id),
                children("service_usages", id),
                children("booking_change_requests", id).stream()
                        .map(
                                r ->
                                        new ChangeRow(
                                                r,
                                                db.decode(r.s("requested_changes"), Saved.class)))
                        .toList(),
                audit,
                ledger.balance(id));
    }

    private List<Row> children(String table, long id) {
        return db.rows("SELECT * FROM " + table + " WHERE booking_id=? ORDER BY id DESC", id);
    }

    @PreAuthorize("hasAuthority('booking.read.any')")
    public Calendar calendar(LocalDate from, LocalDate until, Long type) {
        db.require(
                from != null
                        && until != null
                        && until.isAfter(from)
                        && !until.isAfter(from.plusDays(62)),
                "dates");
        var days = from.datesUntil(until).toList();
        var rooms =
                db.rows(
                        """
                SELECT r.id,r.room_number,f.name AS floor_name,t.name AS type_name FROM rooms r
                JOIN floors f ON f.id=r.floor_id JOIN room_types t ON t.id=r.room_type_id
                WHERE (r.sell_from<? AND (r.sell_until IS NULL OR r.sell_until>?)) AND (? IS NULL OR r.room_type_id=?)
                ORDER BY f.sort_order,f.id,t.name,r.room_number
                """,
                        until,
                        from,
                        type,
                        type);
        var cells = new HashMap<Long, Map<LocalDate, Cell>>();
        for (var row :
                db.rows(
                        """
                SELECT c.room_id,c.stay_date,c.room_block_id,rb.reason,a.status AS allocation_status,br.status,
                    b.id AS booking_id,b.booking_code FROM room_night_claims c
                JOIN rooms r ON r.id=c.room_id LEFT JOIN room_blocks rb ON rb.id=c.room_block_id
                LEFT JOIN room_allocations a ON a.id=c.room_allocation_id LEFT JOIN booking_rooms br ON br.id=a.booking_room_id
                LEFT JOIN bookings b ON b.id=br.booking_id
                WHERE c.stay_date>=? AND c.stay_date<? AND (? IS NULL OR r.room_type_id=?)
                """,
                        from,
                        until,
                        type,
                        type)) {
            var state =
                    row.id("room_block_id") != null
                            ? "BLOCK"
                            : switch (row.s("allocation_status")) {
                                case "OCCUPIED" -> "CHECKED_IN";
                                case "COMPLETED" -> "CHECKED_OUT";
                                default -> "PENDING".equals(row.s("status"))
                                        ? "PENDING"
                                        : "CONFIRMED";
                            };
            cells.computeIfAbsent(row.id("room_id"), ignored -> new HashMap<>())
                    .put(
                            row.date("stay_date"),
                            new Cell(
                                    state,
                                    row.id("booking_id") == null
                                            ? row.s("reason")
                                            : row.s("booking_code"),
                                    row.id("booking_id")));
        }
        var rows =
                rooms.stream()
                        .map(
                                r ->
                                        new CalendarRow(
                                                r,
                                                days.stream()
                                                        .map(
                                                                d ->
                                                                        cells.getOrDefault(
                                                                                        r.id("id"),
                                                                                        Map.of())
                                                                                .getOrDefault(
                                                                                        d,
                                                                                        new Cell(
                                                                                                "FREE",
                                                                                                null,
                                                                                                null)))
                                                        .toList()))
                        .toList();
        var capacity =
                db.rows(
                        """
                SELECT i.*,t.name AS type_name FROM room_type_inventory i JOIN room_types t ON t.id=i.room_type_id
                WHERE i.stay_date>=? AND i.stay_date<? AND (? IS NULL OR i.room_type_id=?)
                ORDER BY i.stay_date,t.name
                """,
                        from,
                        until,
                        type,
                        type);
        return new Calendar(from, until, days, rows, capacity);
    }

    @PreAuthorize("hasAuthority('booking.cancel.any')")
    public Page cancellations(String kind, Long actor, LocalDate from, LocalDate until, int index) {
        dates(from, until);
        var sql =
                new StringBuilder(
                        """
                FROM booking_cancellations c JOIN bookings b ON b.id=c.booking_id
                LEFT JOIN users u ON u.id=c.processed_by WHERE 1=1
                """);
        var args = new ArrayList<>();
        add(sql, args, " AND c.kind=?", kind);
        add(sql, args, " AND c.processed_by=?", actor);
        period(sql, args, "c.processed_at", from, until);
        return page("SELECT c.*,b.booking_code,u.email AS actor ", sql, args, "c.id DESC", index);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    public Page payments(
            String kind, String status, String method, LocalDate from, LocalDate until, int index) {
        dates(from, until);
        var sql =
                new StringBuilder(
                        "FROM payment_transactions p JOIN bookings b ON b.id=p.booking_id WHERE"
                                + " 1=1");
        var args = new ArrayList<>();
        add(sql, args, " AND p.kind=?", kind);
        add(sql, args, " AND p.status=?", status);
        add(sql, args, " AND p.method=?", method);
        period(sql, args, "COALESCE(p.paid_at,p.created_at)", from, until);
        return page("SELECT p.*,b.booking_code ", sql, args, "p.id DESC", index);
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    public Page debts(String direction, int index) {
        var sql =
                new StringBuilder(
                        """
                FROM (SELECT b.id AS booking_id,b.booking_code,b.contact_name,b.status,
                  COALESCE((SELECT SUM(c.amount) FROM booking_charges c WHERE c.booking_id=b.id AND c.status='POSTED'),0) AS total,
                  COALESCE((SELECT SUM(CASE WHEN p.kind='REFUND' THEN -p.amount ELSE p.amount END)
                    FROM payment_transactions p WHERE p.booking_id=b.id AND p.status='SUCCEEDED'),0) AS paid
                FROM bookings b) d WHERE
                """);
        sql.append(
                "REFUND".equals(direction)
                        ? " total<paid"
                        : "DEBT".equals(direction) ? " total>paid" : " total<>paid");
        return page(
                "SELECT d.*,total-paid AS balance ",
                sql,
                List.of(),
                "ABS(total-paid) DESC,d.booking_id DESC",
                index);
    }

    @PreAuthorize("hasAuthority('invoice.manage')")
    public Page invoices(String search, String status, LocalDate from, LocalDate until, int index) {
        dates(from, until);
        var sql =
                new StringBuilder("FROM invoices i JOIN bookings b ON b.id=i.booking_id WHERE 1=1");
        var args = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            db.require(search.length() <= 150, "input");
            sql.append(
                    " AND (LOWER(i.invoice_number) LIKE ? ESCAPE '!' OR LOWER(b.booking_code) LIKE"
                            + " ? ESCAPE '!' OR LOWER(i.buyer_name) LIKE ? ESCAPE '!')");
            args.addAll(Collections.nCopies(3, like(search)));
        }
        add(sql, args, " AND i.status=?", status);
        period(sql, args, "i.issued_at", from, until);
        return page("SELECT i.*,b.booking_code ", sql, args, "i.id DESC", index);
    }

    @PreAuthorize("hasAuthority('invoice.manage')")
    public Map<String, Object> invoice(long id) {
        var invoice =
                db.one(
                        "SELECT i.*,b.booking_code FROM invoices i JOIN bookings b ON"
                                + " b.id=i.booking_id WHERE i.id=?",
                        id);
        return Map.of(
                "invoice",
                invoice,
                "lines",
                db.rows("SELECT * FROM invoice_lines WHERE invoice_id=? ORDER BY line_number", id),
                "balance",
                ledger.balance(invoice.id("booking_id")));
    }

    @PreAuthorize("hasAnyAuthority('payment.collect','payment.refund')")
    public List<Row> shifts() {
        return db.rows(
                """
                SELECT w.*,w.opening_cash+COALESCE((SELECT SUM(CASE WHEN p.kind='REFUND' THEN -p.amount ELSE p.amount END)
                FROM payment_transactions p WHERE p.work_session_id=w.id AND p.status='SUCCEEDED'),0) AS expected_cash
                FROM work_sessions w JOIN employees e ON e.id=w.employee_id WHERE e.user_id=? ORDER BY w.id DESC LIMIT 25
                """,
                db.actor());
    }

    @PreAuthorize("hasAnyAuthority('service.read','service.manage')")
    public List<Row> services() {
        return db.rows(
                "SELECT s.*,c.name AS category_name,c.is_active AS category_active FROM services s"
                    + " JOIN service_categories c ON c.id=s.category_id ORDER BY c.name,s.name");
    }

    @PreAuthorize("hasAuthority('service.manage')")
    public List<Row> topServices(LocalDate from, LocalDate until) {
        dates(from, until);
        var sql =
                new StringBuilder(
                        """
                SELECT s.id,s.name,s.unit,SUM(u.quantity) AS quantity,SUM(c.amount) AS amount
                FROM service_usages u JOIN services s ON s.id=u.service_id JOIN booking_charges c ON c.service_usage_id=u.id
                WHERE u.status='ACTIVE' AND c.status='POSTED' AND c.kind='SERVICE'
                """);
        var args = new ArrayList<>();
        period(sql, args, "u.used_at", from, until);
        sql.append(" GROUP BY s.id,s.name,s.unit ORDER BY amount DESC,s.id LIMIT 20");
        return db.rows(sql.toString(), args.toArray());
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<Row> reconcile(LocalDate from, LocalDate until) {
        db.require(
                from != null
                        && until != null
                        && until.isAfter(from)
                        && !until.isAfter(from.plusDays(62)),
                "dates");
        return db.rows(
                """
                WITH nights AS (
                  SELECT n.inventory_room_type_id AS type_id,n.stay_date,
                    SUM(CASE WHEN n.inventory_state='HELD' THEN 1 ELSE 0 END) AS held,
                    SUM(CASE WHEN n.inventory_state='RESERVED' THEN 1 ELSE 0 END) AS reserved
                  FROM booking_room_nights n WHERE n.stay_date>=? AND n.stay_date<? AND n.inventory_state<>'RELEASED'
                  GROUP BY n.inventory_room_type_id,n.stay_date
                ), claims AS (
                  SELECT r.room_type_id AS type_id,c.stay_date,
                    SUM(CASE WHEN c.room_block_id IS NOT NULL THEN 1 ELSE 0 END) AS blocked,
                    SUM(CASE WHEN c.room_allocation_id IS NOT NULL THEN 1 ELSE 0 END) AS allocated
                  FROM room_night_claims c JOIN rooms r ON r.id=c.room_id
                  WHERE c.stay_date>=? AND c.stay_date<? GROUP BY r.room_type_id,c.stay_date
                )
                SELECT i.*,t.name AS type_name,COALESCE(n.held,0) AS expected_held,COALESCE(n.reserved,0) AS expected_reserved,
                  COALESCE(c.blocked,0) AS expected_blocked,COALESCE(c.allocated,0) AS allocated
                FROM room_type_inventory i JOIN room_types t ON t.id=i.room_type_id
                LEFT JOIN nights n ON n.type_id=i.room_type_id AND n.stay_date=i.stay_date
                LEFT JOIN claims c ON c.type_id=i.room_type_id AND c.stay_date=i.stay_date
                WHERE i.stay_date>=? AND i.stay_date<? AND (i.held_rooms<>COALESCE(n.held,0)
                  OR i.reserved_rooms<>COALESCE(n.reserved,0) OR i.blocked_rooms<>COALESCE(c.blocked,0)
                  OR COALESCE(n.held,0)+COALESCE(n.reserved,0)<>COALESCE(c.allocated,0))
                ORDER BY i.stay_date,i.room_type_id
                """,
                from,
                until,
                from,
                until,
                from,
                until);
    }

    private void dates(LocalDate from, LocalDate until) {
        db.require(from == null || until == null || until.isAfter(from), "dates");
    }

    private void period(
            StringBuilder sql, List<Object> args, String column, LocalDate from, LocalDate until) {
        var zone = db.zone();
        add(
                sql,
                args,
                " AND " + column + ">=?",
                from == null ? null : ts(from.atStartOfDay(zone).toInstant()));
        add(
                sql,
                args,
                " AND " + column + "<?",
                until == null ? null : ts(until.atStartOfDay(zone).toInstant()));
    }

    private void add(StringBuilder sql, List<Object> args, String condition, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            sql.append(condition);
            args.add(value);
        }
    }

    private Page page(
            String select, StringBuilder from, List<Object> parameters, String order, int index) {
        int page = Math.max(0, Math.min(100000, index));
        var args = new ArrayList<>(parameters);
        long total = db.count("SELECT COUNT(*) " + from, args.toArray());
        args.add(SIZE);
        args.add(page * SIZE);
        return new Page(
                db.rows(select + from + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray()),
                page,
                total);
    }
}
