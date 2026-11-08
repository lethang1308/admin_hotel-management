# Dashboard lễ tân

Mở `/admin/receptionist`. Tài khoản RECEPTIONIST vào trang này sau khi đăng nhập. ADMIN có thể mở để hỗ trợ vận hành.

Quản lý đặt phòng tại quầy (tra cứu phòng trống, walk-in, xếp/đổi phòng, gia hạn, rút ngắn và no-show) được hướng dẫn trong [CMS_RECEPTIONIST_BOOKINGS.md](CMS_RECEPTIONIST_BOOKINGS.md).

Thủ tục nhận/trả phòng, QR, đăng ký khách, quyết toán, PDF và hàng đợi dọn phòng xem tại [CMS_RECEPTIONIST_STAYS.md](CMS_RECEPTIONIST_STAYS.md).

Ghi dịch vụ, thu cọc/thanh toán nhiều lần, VietQR, chứng từ, yêu cầu hoàn tiền và hóa đơn công ty xem tại [CMS_RECEPTIONIST_CASHIER.md](CMS_RECEPTIONIST_CASHIER.md). Mô-đun này bổ sung Flyway V7.

## Ca trực và số liệu

Dashboard hiển thị ca của chính nhân viên đang đăng nhập. Mở ca với số tiền mặt ban đầu và ghi chú, kết ca với số tiền kiểm đếm và lời giải thích khi có chênh lệch. Tiền mặt dự kiến bằng tiền đầu ca cộng thu CASH thành công, trừ hoàn CASH thành công. Chuyển khoản được theo dõi trong công nợ của đơn, riêng với két tiền mặt.

Các chỉ số khách đến và khách đi đếm từng dòng phòng cần xử lý trong ngày kinh doanh của khách sạn. Phòng đang ở lấy từ lượt xếp phòng thực tế đang mở. Phòng sẵn sàng cần sạch, đang kinh doanh, hạng phòng và tầng hoạt động, không bị khóa bảo trì hôm nay và không có khách đang ở. Số liệu ca cần quyền `report.shift`, danh sách đơn cần `booking.read.any`, chỉ số phòng cần `room.read`.

## Thao tác tại quầy

- **Đến hôm nay** hiển thị khách, SĐT, mã đơn, phòng dự kiến, giờ đến, trạng thái phòng, công nợ và cọc còn thiếu. Chọn Nhận phòng để nhập khách lưu trú và giấy tờ. Có thể ghi khoản thu đã đối chiếu bằng tiền mặt hoặc chuyển khoản. Khoản thu và nhận phòng nằm trong cùng giao dịch. Nếu phòng chưa sẵn sàng hoặc giấy tờ không hợp lệ, giao dịch được hoàn tác. Đổi phòng qua Chi tiết đơn bằng quy trình phê duyệt hiện có.
- **Đi hôm nay** mở bảng kê, công nợ, dự kiến phụ phí gồm thuế và điều chỉnh trả sớm. Có thể ghi thêm dịch vụ trước khi chốt. Khi xác nhận, hệ thống kiểm tra lại chênh lệch, thu tiền và trả phòng cùng giao dịch. Công nợ cuối đơn phải được chốt. Tiền thừa chuyển sang quy trình hoàn cần quyền `payment.refund` và khoản thu gốc.
- **Đơn chờ xác nhận** sắp theo hạn giữ chỗ, kèm đồng hồ đếm ngược. Xác nhận hoặc từ chối cần lý do. Đơn đã hết hạn giữ chỗ được loại khỏi hàng đợi xác nhận. Backend luôn kiểm tra hạn khi xử lý.
- **Cảnh báo** gồm khách CONFIRMED trễ giờ đến từ hai tiếng và khách CHECKED_IN đã quá giờ trả, kể cả ngày trước. Có liên kết gọi khách, xử lý no-show, trả phòng và mở Chi tiết để gia hạn.
- Mỗi danh sách lấy tối đa 50 mục theo thứ tự giờ vận hành. Liên kết toàn bộ đơn dẫn đến màn hình tìm kiếm và phân trang hiện có.

Giấy tờ khách lưu trú dùng cơ chế mã hóa của mô-đun khách hàng. Cấu hình `CUSTOMER_DOCUMENT_ENCRYPTION_KEY` theo hướng dẫn của dự án để nhận phòng. Phụ phí và phí trả sớm tái sử dụng chính sách từ mô-đun giá và đặt phòng.

## Thông báo và cập nhật

Bell trên header hiển thị thông báo riêng của tài khoản. Thông báo được tạo từ outbox sau khi giao dịch đặt phòng đã commit, với khóa chống lặp cho từng người nhận. Các sự kiện gồm tạo/xác nhận/hủy/no-show/nhận-trả phòng, đổi phòng/ngày và ghi thu-hoàn tiền. Lịch sử lưu trong `notifications` và `notification_deliveries` bằng kênh IN_APP.

SSE gửi tín hiệu cập nhật để giao diện lấy thông báo của chính người đăng nhập và làm mới vùng số liệu, đồng thời giữ tab đang chọn và biểu mẫu đang nhập. SSE kiểm tra lại trạng thái tài khoản, vai trò, quyền, phiên đăng nhập và phiên bản bảo mật. Mỗi tài khoản có tối đa bốn kết nối, mỗi tiến trình tối đa 200. Heartbeat 15 giây và tái kết nối sau khi hết thời gian của kết nối. Dashboard lấy lại dữ liệu sau 30 giây để phục hồi khi mất mạng. Bộ đọc outbox thử lại mỗi năm giây nếu giao dịch chưa được xử lý. Âm báo được bật bằng lựa chọn trên chính trang đang mở.

Các cấu hình có thể điều chỉnh trong application properties:

- `app.reception.notifications-enabled` mặc định true, false trong kiểm thử mặc định.
- `app.reception.dispatch-interval-ms` mặc định 5000.

Khi triển khai qua reverse proxy, bật hỗ trợ truyền SSE và giữ thời gian chờ lớn hơn heartbeat. Dữ liệu được lưu bền trong DB, các kết nối SSE do từng tiến trình giữ. Dashboard vẫn đối chiếu lại định kỳ khi chạy nhiều tiến trình.

## Sidebar

Từng mô-đun được gom vào một nhóm thu gọn, tự mở theo trang hiện tại và chỉ mở một nhóm tại một thời điểm. Nhóm dùng phần tử details/summary có bàn phím hỗ trợ. Sidebar giới hạn theo chiều cao màn hình, chỉ vùng menu cuộn. Cơ chế cuộn native thay PerfectScrollbar trong layout CMS để chiều dài vùng cuộn bám đúng nội dung sau khi đóng/mở nhóm.

## Endpoints

| Endpoint | Chức năng và quyền |
|---|---|
| GET `/admin/receptionist` | Dashboard, booking.read.any hoặc report.shift |
| GET `/admin/receptionist/fragment` | Vùng số liệu HTML cập nhật |
| GET `/admin/api/receptionist/dashboard` | Snapshot JSON theo quyền |
| POST `/admin/receptionist/shift/open`, `/shift/close` | Ca của chính nhân viên, report.shift |
| POST `/admin/receptionist/quick-checkin` | Nhận phòng, booking.checkin và payment.collect khi có khoản thu |
| POST `/admin/receptionist/quick-checkout` | Trả phòng, booking.checkout và payment.collect khi có khoản thu |
| GET `/admin/api/receptionist/rooms/{line}/preview` | Xem số liệu trước nhận/trả, booking.read.any và quyền thao tác |
| POST `/admin/receptionist/quick-confirm/{id}`, `/quick-reject/{id}` | Xác nhận/từ chối, booking.confirm |
| POST `/admin/receptionist/no-show/{id}` | No-show, booking.no_show |
| POST `/admin/receptionist/services` | Dịch vụ trước trả phòng, service.usage.manage |
| GET `/admin/api/receptionist/stream` | SSE, notification.manage hoặc booking.read.any |
| GET `/admin/api/receptionist/notifications` | Thông báo của chính tài khoản |
| POST `/admin/api/receptionist/notifications/read` | Đánh dấu các ID của chính tài khoản, notification.manage |

Mọi endpoint được giới hạn bởi vai trò ADMIN/RECEPTIONIST của CMS. Mọi POST yêu cầu CSRF. Các luồng nghiệp vụ giữ khóa, kiểm tra trùng phòng, quy tắc tiền và audit từ mô-đun đặt phòng. Bộ kiểm thử mới bao gồm ca đồng thời, công nợ/phụ phí, hoàn tác nhận phòng, phân quyền, notification chống lặp, quyền sở hữu và kết thúc SSE khi hết phiên.

Thực thi `mvnw.cmd test` tại backend. Cơ sở dữ liệu MySQL cho kiểm thử phải là schema riêng, vì fixture được dọn giữa các bài.

## Kết quả kiểm chứng

Bộ hồi quy H2 đạt 113 bài, một bài MySQL opt-in được bỏ qua trong lệnh mặc định. Bộ MySQL riêng đạt 16/16 bài cho Dashboard lễ tân và các luồng đặt phòng, thanh toán, hóa đơn hiện có. Luồng HTTP thực đã nhận sự kiện SSE sau khi trạng thái đơn được commit và đối chiếu thông báo chưa đọc. JavaScript qua kiểm tra cú pháp. Kiểm tra trực quan qua công cụ điều khiển trình duyệt bị dừng vì công cụ không xác định chắc URL cửa sổ Windows.

Mô-đun sử dụng schema hiện có, không bổ sung migration.

Mô-đun 5 — Sơ đồ phòng, dọn phòng, bảo trì, lịch sử khách, yêu cầu đặc biệt và email: [CMS_RECEPTIONIST_ROOM_CARE.md](CMS_RECEPTIONIST_ROOM_CARE.md). Cần Flyway V8.
