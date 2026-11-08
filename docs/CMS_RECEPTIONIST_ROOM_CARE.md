# Lễ tân · Quản lý phòng và hỗ trợ khách

Mô-đun 5 tích hợp vào sơ đồ phòng, hàng đợi dọn phòng, bảo trì, hồ sơ khách và chi tiết booking. Khởi động lại backend để Flyway áp dụng **V8__reception_guest_care.sql**. Schema hiện có **62 bảng, 133 khóa ngoại, 84 CHECK constraints**; DBML trong database.txt đã đồng bộ. V8 giữ dữ liệu cũ, mặc định các việc dọn đã có thành TURNOVER/NORMAL.

## Sơ đồ và tác nghiệp phòng

Mở **Bàn lễ tân → Sơ đồ phòng** (`/admin/rooms/grid`). Sơ đồ giữ bộ lọc tầng, khu vực, hạng và trạng thái, KPI và màu AVAILABLE/OCCUPIED/DIRTY/CLEANING/MAINTENANCE/INACTIVE.

Chọn thẻ phòng để mở popup (báo hỏng có form ngay trong popup):
- Phòng AVAILABLE: tạo walk-in theo hạng đang xem, hoặc mở danh sách khách đến hôm nay để xếp phòng. Walk-in vẫn cần xem tình trạng trống và xếp phòng qua cơ chế hiện có; shortcut không tự chiếm phòng.
- Phòng có phân đoạn OCCUPIED: mã đơn, khách, công nợ; mở lịch sử khách, thêm dịch vụ hoặc check-out bằng modal dùng chung.
- Phòng trống/bẩn/đang ở: ghi chú và tạo yêu cầu dọn, có tùy chọn ưu tiên cao.
- Báo hỏng ngay trong popup đã chọn đúng số phòng; cũng có trang theo dõi yêu cầu riêng.

Dữ liệu booking trong popup chỉ được tải khi có booking.read.any; các nút nghiệp vụ vẫn kiểm tra quyền tại server.

## Dọn phòng và nghiệm thu

`/admin/receptionist/housekeeping` có bộ lọc PENDING, IN_PROGRESS, COMPLETED, VERIFIED, CANCELLED. Trang mặc định hiển thị việc chưa xong; ưu tiên HIGH đứng trước, tối đa 200 việc. Hiển thị người dọn, giờ bắt đầu/xong/nghiệm thu, ghi chú và loại việc.

**TURNOVER**: phòng trống cần dọn hoặc khách vừa trả/đổi phòng. Yêu cầu chủ động đưa phòng sang DIRTY và tạo PENDING. Quy trình **Bắt đầu → Đã dọn xong → Nghiệm thu sạch**:
- start: IN_PROGRESS, started_at, nhân viên của người thực hiện nếu có; phòng CLEANING.
- complete: COMPLETED và completed_at; vẫn CLEANING.
- verify: VERIFIED, verified_by/verified_at; chỉ AVAILABLE nếu không còn việc chưa nghiệm thu.

**STAY_OVER**: khách vẫn đang ở. Quy trình task giống trên nhưng luôn giữ phòng OCCUPIED và giữ nguyên allocation/claims. Khi khách trả/đổi phòng, các việc STAY_OVER chưa xong được CANCELLED; tạo việc TURNOVER mới để phòng được vệ sinh sau trả.

Cùng một phòng có việc đang hoạt động thì yêu cầu mới dùng lại task; có thể tăng ưu tiên mà không sinh việc trùng. Ghi chú của lần yêu cầu mới được ghi trong audit; nội dung task ban đầu được giữ. Cả thao tác dọn và đổi trạng thái đòi housekeeping.manage + room.status.update. Room lock và khóa điều phối tồn kho bảo vệ việc dọn/check-in/check-out đồng thời. Luồng priority của mô-đun 3 cũng gắn HIGH.

## Báo hỏng và khóa bảo trì

`/admin/maintenance`: báo hỏng theo phòng, tiêu đề tối đa 200, mô tả 5.000, ưu tiên LOW/NORMAL/HIGH/URGENT; mã chống gửi lặp 24 ký tự. Yêu cầu lưu OPEN.

Có thể chọn **Khóa phòng bảo trì** với khoảng ngày [từ, đến), lý do tối đa 500:
- Khóa hiện tại chuyển phòng MAINTENANCE; khóa tương lai giữ trạng thái hôm nay.
- Kiểm tra sale window, horizon, allocation/claims và khách còn đang ở. Có xung đột thì hoàn tác cả yêu cầu và block, không đẩy khách đang ở ra khỏi phòng.
- Dùng RoomBlockService, ghi FK maintenance_request_id + room_id, room_night_claims và reconcile blocked/sellable inventory trong cùng transaction.
- Lễ tân mặc định có maintenance.manage, không được cấp thêm room.block.manage. Chỉ được tạo/mở block thông qua yêu cầu bảo trì liên kết; luồng này cần thêm room.status.update. Khóa phòng thông thường vẫn giữ quyền riêng.
- Nếu sự cố nhỏ không khóa, có thể báo và xử lý khi phòng đang OCCUPIED; không đổi trạng thái hay tạo việc dọn tự động.

Nhấn **Bắt đầu sửa** để chuyển IN_PROGRESS; **Đã sửa xong & Mở khóa** để RESOLVED; hoặc hủy kèm lý do. Luồng xử lý giải phóng các block liên kết còn ACTIVE và claims từ hôm nay, cập nhật inventory. Nếu block đã bắt đầu và phòng trống, đưa về CLEANING, tạo việc hậu bảo trì IN_PROGRESS khi chưa có việc hoạt động; buồng phòng hoàn thành rồi nghiệm thu. Block tương lai bị hủy không làm bẩn phòng hôm nay.

Block liên kết phải được mở ở luồng bảo trì, không mở trực tiếp ở trang khóa phòng. Lịch khóa hết hạn không đồng nghĩa đã sửa xong: yêu cầu OPEN/IN_PROGRESS liên kết block đã bắt đầu vẫn ngăn nghiệm thu/đưa phòng AVAILABLE/check-in, cho đến khi được xử lý. Người trực cần theo dõi việc quá lịch; mô-đun không tự kéo dài khoảng khóa bán phòng tương lai.

## Hồ sơ và lịch sử khách

`/admin/customers/{id}`, quyền customer.read:
- Thông tin có sẵn, giấy tờ đã che; tìm theo tên, mã, email, điện thoại chuẩn hóa khoảng trắng/dấu gạch/dấu chấm/ngoặc và hash giấy tờ.
- Tổng đơn, số lượt đã ở, đêm phòng thực ở, chi tiêu thực thu.
- Một booking đã check-in tính một lượt; nhiều phòng/chuyển phòng không cộng lượt trùng. Đêm tính theo ngày nhận/trả thực tế của từng booking_room trong múi giờ khách sạn, tối thiểu một đêm cho stay cùng ngày. Phòng đang ở tính đến hôm nay, không tính toàn bộ đêm dự kiến tương lai.
- Chi tiêu = SUCCEEDED thu − SUCCEEDED hoàn trên tất cả đơn của khách; tiền cọc của đơn tương lai vẫn thuộc thực thu. Không lấy tổng hóa đơn để cộng trùng.
- Tag NEW (0–1), RETURNING (2–4), VIP (từ 5 lượt).
- Tối đa 100 đơn gần nhất cùng nợ/hóa đơn, 200 phân đoạn phòng và 200 dịch vụ. Thống kê tổng vẫn xét toàn bộ.
- customer.update cho phép sửa riêng ghi chú sở thích/dị ứng; có lý do và so khớp nội dung cũ để tránh ghi đè màn hình đã cũ. Thông tin định danh không bị thay bởi form ghi chú.

## Yêu cầu đặc biệt và phụ phí

Trang chi tiết booking có form cấp booking và từng dòng phòng. Chọn các nhu cầu chuẩn: yên tĩnh, tầng cao/thấp, không hút thuốc, thông phòng, đến muộn, nhận sớm/trả muộn, cũi, chăn gối, bàn là; nhập yêu cầu khách thấy, nội bộ riêng và yêu cầu dòng phòng. Các trường hiện có special_requests/internal_notes tiếp tục dùng, bảng booking_room_preferences lưu lựa chọn và revision.

- Chọn EXTRA_BED / EXTRA_GUEST bằng số lượng 0–10; chỉ là đơn vị phụ phí, không tự tăng số người đăng ký hoặc vượt kiểm tra sức chứa. Giới hạn khách và dữ liệu check-in vẫn theo booking.
- Bắt buộc **Xem phụ phí & VAT** trước khi lưu. Truy vấn chính sách active đúng hạng hoặc toàn khách sạn, priority/id giảm dần. Chưa có chính sách phù hợp thì chặn; không tự đoán giá.
- FIXED tính một lần trên mỗi đơn vị cho kỳ ở; PERCENT_NIGHT tính theo tổng net các đêm phòng còn hiệu lực. PER_HOUR không dùng cho hai loại add-on này.
- VAT lấy snapshot chính sách booking; tiền VND làm tròn nguyên đồng. Lưu SURCHARGE/TAX trong sổ cái với source_key và policy ID. Đổi nhu cầu void các charges add-on cũ rồi ghi bộ mới, không xóa lịch sử.
- Token giá và revision ngăn lưu quote cũ hoặc gửi lại làm cộng phí hai lần. Hóa đơn ISSUED chặn sửa.
- Hủy/từ chối/hết hạn/no-show trước check-in void phụ phí add-on chưa sử dụng và VAT kèm theo; giữ lịch sử. Preview no-show cũng trừ khoản này để số tiền xem trước khớp khi xử lý.
- Thay đổi ngày/hạng sau khi đã ghi add-on không tự sửa phí cũ; người vận hành phải xem lại và lưu quote nhu cầu mới theo kỳ ở đã thay đổi. Phí nhận sớm/trả muộn vẫn do thủ tục check-in/out hiện có tính.
- Dashboard khách đến có ⭐ khi có yêu cầu, modal nhận/trả hiển thị ghi chú và tag. Yêu cầu khách thấy và dòng phòng vào email; internal_notes không gửi khách.

## Email, thông báo và lịch sử gửi

Tự động tạo snapshot thư từ sự kiện xác nhận, đổi phòng/ngày, đổi yêu cầu, hủy và no-show. Cùng event_key chỉ xếp một thư. Nội bộ vẫn dùng ReceptionOutboxDispatcher + chuông SSE, độc lập với worker SMTP.

**Gửi lại email xác nhận** trên booking CONFIRMED/CHECKED_IN cần notification.manage + booking.read.any. Có thể sửa địa chỉ nhận riêng cho lần gửi, không đổi contact_email của booking. Có mã chống lặp, audit và lịch sử recipient/status/attempts/sent_at/next_retry_at/last_error trên chi tiết đơn.

Thư có tên/địa chỉ/hotline khách sạn, mã booking, phòng/ngày/số người, giờ nhận/trả và chính sách cọc/hủy đã chấp nhận. Thư xác nhận có ảnh QR đính kèm để tra cứu tại quầy, token ngẫu nhiên lưu hash trong booking_qr_tokens, hết hạn sau ngày trả dự kiến một ngày. Tạo thư mới không tự thu hồi QR của thư khác; chức năng tạo lại QR tại quầy vẫn thu hồi token cũ theo luồng có sẵn. QR dùng để tra cứu, không bỏ qua quyền, kiểm tra giấy tờ hay xác nhận trạng thái tại check-in.

guest_messages lưu snapshot; outbox GUEST_MESSAGE và notification_deliveries.EMAIL được ghi cùng transaction booking. Khách có tài khoản liên kết còn có bản ghi notifications riêng; khách walk-in không có user thì notification_id để NULL, vẫn có đầy đủ lịch sử delivery. Không gửi email trực tiếp trong transaction nghiệp vụ.

Worker claim có khóa và lease 60 giây, SMTP ngoài transaction, kết quả được ghi transaction riêng:
- PENDING: đang chờ, chưa chứng minh đã gửi.
- SENT: SMTP đã chấp nhận; không bảo đảm thư vào Inbox.
- FAILED: gửi chưa được, lý do tổng quát không lộ credential; thử lại theo backoff, tối đa 5 lần. Worker chết được claim lại khi hết lease; lần cuối hết lease chuyển FAILED để theo dõi.
- SMTP chỉ có bảo đảm xử lý ít nhất một lần: có thể gửi lại nếu máy chủ đã nhận nhưng tiến trình chết trước khi lưu SENT. Không tuyên bố exactly-once.
- Không có SMTP hợp lệ thì không đánh dấu SENT và không làm hỏng giao dịch đặt phòng. Có thể gửi lại thủ công sau khi sửa cấu hình.

Cấu hình trong .env (không đưa mật khẩu thật vào Git):
```properties
GUEST_MAIL_ENABLED=true
GUEST_MAIL_FROM=hotel@example.com
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=your-smtp-user
SMTP_PASSWORD=your-smtp-password
SMTP_AUTH=true
SMTP_STARTTLS=true
```
Đổi thành thông tin SMTP thực và khởi động lại. Mailpit/MailHog cục bộ có thể dùng cổng 1025, SMTP_AUTH=false, SMTP_STARTTLS=false. Mặc định gửi thật bị tắt (`GUEST_MAIL_ENABLED=false`). Bộ test tắt worker và dùng transport giả, không gửi thư ra ngoài. Tích hợp dùng [JavaMailSender/MimeMessageHelper chính thức của Spring](https://docs.spring.io/spring-framework/reference/integration/email.html).

## Endpoint chính

| Endpoint | Quyền |
|---|---|
| GET /admin/rooms/grid | room.read |
| POST /admin/rooms/{id}/dirty | room.status.update + housekeeping.manage |
| GET /admin/receptionist/housekeeping | housekeeping.manage |
| POST /admin/receptionist/housekeeping/{task}/{start,complete,verify} | housekeeping.manage + room.status.update |
| GET, POST /admin/maintenance | maintenance.manage |
| POST /admin/maintenance/{id}/{start,resolve,cancel} | maintenance.manage; mở block cần room.status.update |
| GET /admin/customers/{id} | customer.read |
| POST /admin/customers/{id}/notes | customer.update |
| GET /admin/bookings/{id}/guest-requests/preview | booking.update.any |
| POST /admin/bookings/{id}/guest-requests | booking.update.any |
| POST /admin/bookings/{id}/resend-email | notification.manage + booking.read.any |

Các POST cần CSRF. Các mutation form dùng flash lỗi/thành công; preview JSON no-store. Các endpoint mô-đun trước được giữ. Không thay default role grants.

## Kiểm thử

ReceptionRoomCareIntegrationTest bao phủ: nghiệm thu có thứ tự, không tạo trùng việc, giữ OCCUPIED khi dọn giữa kỳ, checkout hủy việc giữa kỳ, khóa bảo trì và claims/inventory, xung đột rollback, bảo trì tương lai, dọn sau sửa/khóa hết hạn, lịch sử đêm/chi tiêu và giấy tờ che, tìm điện thoại, phụ phí/VAT/token/revision, chặn invoice, giữ ghi chú nội bộ riêng, hàng đợi email/dedup/retry/lease, MIME tiếng Việt/QR và phân quyền/CSRF/HTML render.

Kết quả ngày 08/11/2026, chạy trên schema riêng:

- Toàn bộ Maven suite H2: **162 bài, 161 đạt, 1 bỏ qua** (bài MySQL opt-in), BUILD SUCCESS.
- Hồi quy MySQL 8.0.30: **85/85 đạt**, gồm schema/migration, khách sạn/phòng, booking, ca/thu ngân, dashboard và nghiệp vụ lễ tân.
- Sau bản sửa cuối, kiểm tra lại Guest Care + Booking Operations + Booking Management trên MySQL: **34/34 đạt**, gồm 16 bài module 5 và hủy phụ phí chưa sử dụng. Các schema thử nghiệm được dọn bởi helper sau khi chạy.
- Backend compile thành công; JavaScript hợp lệ; git diff không có lỗi khoảng trắng.
- Database cục bộ đã có V8: đối chiếu 62 bảng, 133 FK, 84 CHECK và xác nhận Flyway validate hợp lệ cả 8 migration. Checksum V8 đã được đồng bộ sau khi ứng dụng tự áp dụng bản SQL tương đương trong lúc biên dịch; không sửa dữ liệu nghiệp vụ.

Khởi động lại backend để nhận đầy đủ mã/config mới; Reload Maven nếu IDE chưa nhận spring-boot-starter-mail.

Giao diện được kiểm tra qua HTML render và HTTP/CSRF bằng MockMvc; JavaScript qua kiểm tra cú pháp. Chưa xác minh thao tác giao diện trực tiếp trong trình duyệt hoặc gửi qua SMTP thật.
