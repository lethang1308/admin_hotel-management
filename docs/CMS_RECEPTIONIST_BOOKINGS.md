# Lễ tân · Quản lý đặt phòng

Mở `/admin/bookings` bằng tài khoản RECEPTIONIST hoặc ADMIN. Mô-đun sử dụng các bảng và quyền hiện có, không cần thêm migration. Dashboard và ca trực được mô tả trong [CMS_RECEPTIONIST.md](CMS_RECEPTIONIST.md).

## Thao tác tại quầy

1. **Danh sách đơn:** dùng các bộ lọc Tất cả, Khách đến hôm nay, Khách đi hôm nay, Đơn chờ duyệt, Đang lưu trú. Có thể kết hợp mã đơn, tên, email, SĐT, nguồn, trạng thái, loại phòng và kỳ ở. SĐT được tìm cả khi có dấu cách, dấu chấm, ngoặc hoặc gạch nối. Bộ lọc ngày/loại phòng và bộ lọc ca trực phải khớp cùng một dòng phòng. Số trên các bộ lọc nhanh là tổng số đơn trong toàn khách sạn, không phụ thuộc ô tìm kiếm. Mỗi đơn hiện phòng được xếp, tình trạng phòng hiện tại, tổng phải thu, thu ròng và công nợ.
2. **Kiểm tra phòng trống:** nút trên header mở bảng tra cứu ngày đến/đi, số người lớn/trẻ em **trên mỗi phòng** và số phòng cần thuê. Kết quả gồm tồn từng đêm, số phòng vật lý trống liên tục, sức chứa, tiện nghi, giá trung bình trước thuế và tổng/cọc của toàn bộ số phòng đã chọn. Tùy chọn nhận ngay chỉ lấy phòng sạch, không có khách hoặc lượt ở trước chưa kết thúc. Chọn đặt phòng để điền sẵn form tạo đơn. Tra cứu chỉ đọc; không giữ chỗ hoặc sửa bộ đếm tồn. Kết quả được kiểm tra lại khi lưu đơn.
3. **Đặt tại quầy:** tìm khách bằng liên hệ hoặc CCCD/hộ chiếu đầy đủ, chọn để điền hồ sơ liên hệ; hoặc bấm Khách mới. Tìm giấy tờ gửi POST có CSRF, đối chiếu hash và chỉ trả số đã che. Quốc tịch của khách có sẵn được giữ trong hồ sơ. Thêm tối đa 12 dòng phòng, mỗi dòng có loại phòng, ngày và số khách riêng. Báo giá cập nhật sau khi sửa; chỉ giá đã xem mới được gửi khi tạo đơn. Backend luôn tính lại giá và khóa tồn khi ghi.
4. **Xác nhận/từ chối:** mở chi tiết đơn, nhập lý do. Xác nhận kiểm tra hạn giữ chỗ, số cọc cần thiết và đầy đủ khóa phòng vật lý trước khi chuyển HELD sang RESERVED. Từ chối nhả đêm phòng và lượt voucher. Không có chức năng bỏ qua điều kiện cọc bằng bảo lãnh vì hệ thống chưa có quyền riêng cho nghiệp vụ này.
5. **Walk-in hôm nay:** sau khi tạo đơn, chọn **Thu tiền & Nhận phòng ngay** tại dòng phòng. Nhập người lưu trú, giấy tờ người lớn và khoản tiền thực tế đã thu. Thu tiền, xác nhận và check-in nằm trong một giao dịch; phòng bẩn, thiếu giấy tờ hoặc thiếu cọc sẽ hoàn tác toàn bộ. Tiền mặt cần ca đang mở. Gửi lại thao tác thành công không thu thêm lần nữa. Với đoàn nhiều phòng, khoản thu là công nợ chung của đơn; từng phòng vẫn nhận độc lập.
6. **Xếp phòng:** áp dụng cho dòng PENDING/CONFIRMED, chọn phòng cùng loại. Bảng chọn giải thích phòng ngoài kỳ bán, trùng mã đơn, khóa bảo trì hoặc chưa sạch. Với kỳ ở tương lai, lịch từng đêm quyết định khả dụng; việc phòng đang có khách hôm nay không tự cấm đặt tương lai. Khi nhận hôm nay cần phòng sạch và khách trước đã kết thúc. Lưu sẽ đóng lượt xếp cũ và tạo lượt mới để giữ lịch sử; tiền và tồn theo loại không đổi.
7. **Đổi phòng/gia hạn/rút ngắn:** chọn nút tương ứng, chọn phòng/ngày mới, **Xem trước chênh lệch**, rồi **Lưu yêu cầu đã xem**. Duyệt yêu cầu trong phần Điều chỉnh đơn bên dưới. Giá hoặc phiên bản đơn thay đổi thì phải xem và lập yêu cầu lại. Phòng cũ đang ở được chuyển sang DIRTY, tạo việc dọn phòng và đóng/mở phân đoạn khách khi chuyển phòng. Nếu gia hạn trùng lịch, chọn phòng khác trống liên tục cho phần ở còn lại; phương án này chuyển khách khi yêu cầu được duyệt, không lên lịch chuyển tự động vào ngày hết hạn cũ. Chọn trả sớm ngay hôm nay sẽ dẫn đến luồng **Trả phòng** để ghi giờ thực tế, chốt phí/công nợ và tạo việc dọn phòng.
8. **No-show:** mở mục Vắng mặt trên dòng CONFIRMED đã quá giờ nhận dự kiến, xem phí và tiền dự kiến hoàn, nhập lý do rồi xác nhận. Hệ thống dùng chính sách chụp lúc đặt, nhả phòng và tạo bút toán điều chỉnh. Các dòng khác trong cùng đơn tiếp tục hoạt động. Khoản tiền thừa được xử lý bằng luồng hoàn tiền có quyền và liên kết khoản thu gốc.

## Giá và tính toàn vẹn

- Đêm giữ nguyên giữ giá/voucher đã chấp nhận. Đêm mới hoặc nâng hạng dùng giá hiện hành; thuế dùng chính sách của đơn. Phí bỏ đêm dùng chính sách hủy đã chụp. Xem trước thể hiện chênh lệch, phí và tiền dự kiến hoàn; bước duyệt kiểm tra lại.
- `booking_room_nights` lưu revision; không xóa lịch sử đêm đã điều chỉnh. `booking_charges` là nguồn tính tổng và công nợ. Số âm là tiền khách còn được hoàn, không tự phát sinh giao dịch hoàn.
- Mọi thay đổi tồn/phòng giữ thứ tự khóa của dịch vụ lõi: khách sạn, voucher/booking khi cần, tồn theo loại/ngày và phòng theo ID. Khóa chính `(room_id, stay_date)` bảo vệ việc một phòng chỉ có một chủ giữ trong một đêm. Các thay đổi ghi audit, lịch sử và outbox cùng giao dịch.
- Tra cứu tồn sử dụng cả dữ liệu đêm phòng, tồn đã lưu và phòng vật lý liên tục. Nếu thiếu bản ghi tồn, kết quả suy ra có cờ `estimated`; GET không chạy đối soát và không ghi dữ liệu.

## Endpoint CMS bổ sung

| Endpoint | Quyền |
|---|---|
| GET `/admin/bookings/availability` | `room.read` hoặc `booking.create` |
| POST `/admin/bookings/quote` | `booking.create` |
| POST `/admin/bookings/customers/search` | `booking.create` và `customer.read` |
| GET `/admin/bookings/{id}/rooms/{line}/candidates` | `booking.read.any` và `booking.update.any` |
| POST `/admin/bookings/{id}/assign-room` | `booking.update.any` |
| GET `/admin/bookings/{id}/rooms/{line}/change-preview` | `booking.read.any` và `booking.update.any` |
| GET `/admin/bookings/{id}/rooms/{line}/walk-in-preview` | `booking.read.any`, `booking.confirm`, `booking.checkin` |
| POST `/admin/bookings/{id}/walk-in-checkin` | `booking.confirm`, `booking.checkin`; thêm `payment.collect` nếu thu tiền |
| GET `/admin/bookings/{id}/rooms/{line}/no-show-preview` | `booking.read.any` và `booking.no_show` |

Các endpoint JSON trả `Cache-Control: no-store`. Các endpoint POST cần CSRF. Luồng xác nhận, từ chối, điều chỉnh/duyệt, no-show, thanh toán và hoàn tiền tiếp tục dùng endpoint/dịch vụ cũ. Chưa triển khai API công khai cho frontend khách hàng trong mô-đun này.

## Kiểm thử

`ReceptionBookingManagementIntegrationTest` kiểm tra tồn theo đêm và tồn liên tục, tra cứu chỉ đọc khi thiếu cache, phòng sạch, xếp lại phòng và hoàn tác xung đột, thiếu claim khi xác nhận, tạo nhiều phòng, tra cứu giấy tờ che số/CSRF, walk-in nguyên tử và chống thu lặp, gia hạn bằng phòng thay thế, phí rút ngắn/tiền hoàn, chống duyệt giá cũ, no-show một phần và render HTML.

Chạy `mvnw.cmd -o test` trong backend. Khi chạy trên MySQL, dùng schema kiểm thử riêng vì fixture dọn dữ liệu giữa các bài; URL phải giữ UTC như cấu hình hiện có. Cần `CUSTOMER_DOCUMENT_ENCRYPTION_KEY` để tra cứu giấy tờ và nhận phòng với giấy tờ được mã hóa.

Kết quả kiểm chứng ngày 07/11/2026: toàn bộ suite H2 có 123 bài qua, 1 bài MySQL opt-in được bỏ qua theo cấu hình. Bộ MySQL chạy riêng 26/26 bài của `ReceptionBookingManagementIntegrationTest`, `CmsBookingOperationsIntegrationTest` và `ReceptionDashboardIntegrationTest`. Biên dịch backend và kiểm tra cú pháp năm file JavaScript liên quan đều thành công. HTML được render qua MockMvc; kiểm tra trực quan chưa hoàn tất vì công cụ Windows trả ảnh không khớp cửa sổ Chrome được chọn.
