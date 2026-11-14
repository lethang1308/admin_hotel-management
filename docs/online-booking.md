# Đặt phòng trực tuyến — Role User, giai đoạn 3

Backend sử dụng chung bộ tính giá, tồn kho, phân phòng và sổ công nợ với CMS.
Đơn giữ chỗ có tối đa 5 phòng trong cùng khoảng ngày và 30 đêm. Thời hạn giữ
lấy giá trị nhỏ hơn giữa cấu hình giữ phòng và thời hạn thanh toán cọc. Khi
hết hạn, API trả trạng thái `EXPIRED`, còn CMS lưu `CANCELLED` với lý do
`EXPIRED` để giữ tương thích với báo cáo.

| Phương thức | Đường dẫn dưới `/api/v1` | Dữ liệu chính |
| --- | --- | --- |
| POST | `/bookings/quote` | `rooms: [{typeId,from,to,adults,children}]`, tùy chọn `voucherCode` |
| POST | `/bookings/init` | Như báo giá, thêm `requestKey` duy nhất và `expectedTotal` lấy từ báo giá |
| POST | `/bookings/{id}/addGuests` | Thông tin liên hệ, yêu cầu đặc biệt, `guestIds`, `guests` hoặc `retainedGuestIds` để giữ khách đã lưu trong đơn |
| POST | `/vouchers/apply` | `bookingId`, `voucherCode` (rỗng để bỏ) |
| GET | `/bookings/{id}`, `/bookings/{id}/status` | Chi tiết hoặc trạng thái đơn |
| POST | `/bookings/{id}/payments` | `provider: "SANDBOX"`, `expectedTotal`, `expectedDeposit` |
| POST | `/payments/sandbox/process` | `transactionId`, `action: "SUCCESS" / "FAIL"` |
| POST | `/bookings/{id}/cancel` | `reason` |
| GET | `/users/myBookings` | `page`, `size`, `status`, `sort` |
| GET, POST | `/users/guests` | Danh bạ khách đi cùng |
| PUT, DELETE | `/users/guests/{id}` | Sửa, xóa khách đã lưu |

Các API dùng Bearer JWT. Khi báo giá hoặc tồn phòng thay đổi, máy chủ trả
`409`; thao tác trên đơn hết hạn trả `410`. Gửi lại cùng `requestKey`
trả về đơn đã tạo. Cần dùng một key khác nếu muốn tạo đơn mới.
Thông tin khách được chụp lại theo đơn, độc lập với thay đổi sau đó trong danh bạ.
Giấy tờ là tùy chọn và cần cấu hình khóa `CUSTOMER_DOCUMENT_ENCRYPTION_KEY`.

Chạy Spring Boot và Vite với `VITE_ENABLE_HOTEL_API=true` và
`VITE_API_BASE_URL=http://localhost:8080/api/v1`. Flyway tự áp dụng migration
V10 và V11. Để thử màn thanh toán, tạo chính sách cọc lớn hơn 0 ở
`/admin/policies/deposits/create`, sau đó chọn làm mặc định ở `/admin/hotel`.
Sandbox bật mặc định trong profile dev/test; `BOOKING_SANDBOX_ENABLED=false`
tắt trong dev. Profile sản xuất mặc định tắt. Sandbox không thu tiền thật.
Đơn có mức cọc 0 xác nhận trực tiếp. Sau khi hủy đơn đủ điều kiện,
CMS ghi khoản chờ hoàn và nhân viên có thể xử lý theo luồng hoàn tiền hiện có.

Màn hình xác nhận và thông báo trong ứng dụng hoạt động ngay. Email xác nhận
được đưa vào hàng đợi. Để gửi thật, đặt `GUEST_MAIL_ENABLED=true` và cấu hình SMTP. QR dùng cùng cơ chế xác thực
với chức năng tra cứu của lễ tân.

Kiểm thử backend: `mvnw.cmd -Dtest=OnlineBookingIntegrationTest test`.
