# Lễ tân · Nhận phòng và trả phòng

Mở `/admin/receptionist/stays` hoặc nút **Tra cứu nhận / trả phòng** trên header CMS. Các nút Nhận phòng/Trả phòng trên Dashboard và chi tiết đơn mở cùng một thủ tục. Không cần migration mới: mô-đun dùng các bảng khách lưu trú, QR, phụ phí, giao dịch, hóa đơn và buồng phòng đã có.

## Nhận phòng

1. Tìm mã đơn, tên, email, SĐT hoặc giấy tờ đầy đủ. Tra cứu giấy tờ cần `customer.read`; tìm cả khách đại diện và người đã đăng ký lưu trú. Mọi truy vấn gửi POST có CSRF, không đưa số giấy tờ lên URL.
2. Chọn dòng CONFIRMED đến hôm nay. Bảng xem trước hiện phòng, loại, số khách, cọc thiếu, phụ phí và tổng công nợ. Các khoản tiền là của toàn booking; phòng được chọn quyết định phần điều chỉnh.
3. Nhập đủ số người lớn/trẻ em đúng dòng đã đặt và sức chứa hiện hành. Người lớn cần họ tên và CCCD/hộ chiếu. Trẻ em cần họ tên, ngày sinh; giấy tờ tùy chọn. Người lớn có thể khai ngày sinh. Quốc tịch dùng mã hai chữ, ví dụ VN. Ngày sinh tương lai hoặc quá 130 năm bị từ chối. Giấy tờ được mã hóa AES-256-GCM, tìm bằng HMAC và chỉ hiện số đã che sau lưu.
4. Phòng cần AVAILABLE, còn kinh doanh, tầng/loại hoạt động, đủ claims, không có khách khác, khóa bảo trì, nhiệm vụ dọn chưa nghiệm thu hoặc khoảng trả phòng của khách trước chưa kết thúc. Nếu chưa sẵn sàng, chọn phòng sạch cùng loại trong chính modal (cần `booking.update.any`) hoặc nhập ghi chú và **Báo dọn phòng ưu tiên**. Đổi phòng và nhận khách là cùng giao dịch; không mất dữ liệu khách đã nhập.
5. Nhập khoản thực tế đã thu bằng tiền mặt/chuyển khoản. Hệ thống ghi phụ phí nhận sớm, thu tiền, kiểm tra cọc, đăng ký khách và chuyển phòng/booking sang đang ở cùng giao dịch. Giao dịch thất bại hoàn tác cả việc xếp phòng và ghi thu. Khoản thu có thể bao gồm phụ phí sớm và chỉ tạo một giao dịch thanh toán. Gửi lại sau thành công không thu hoặc tạo khách/phụ phí lần nữa.

## Trả phòng và quyết toán

- Chọn từng phòng đang ở hoặc **Trả tất cả phòng đang ở** trên chi tiết đơn/kết quả tra cứu. Bảng kê hiện khoản POSTED của toàn đơn, thu ròng gồm cả cọc, phí/thuế dự kiến, giảm trừ đêm chưa ở và công nợ sau điều chỉnh.
- Thêm nhanh minibar/dịch vụ ngay trong modal, chọn phòng và số lượng. Khoản dịch vụ được ghi riêng, có khóa chống lặp; sau đó bảng kê tải lại. Thêm dịch vụ thành công vẫn được giữ nếu đóng thủ tục trả phòng.
- Công nợ dương: chọn ghi thu, nhập số thực tế đã thu. Tiền mặt cần ca đang mở của chính nhân viên. Trả phòng cuối phải hết nợ dương. Trả một phần đoàn có thể giữ công nợ chung cho các phòng còn lại.
- Công nợ âm: hiện tiền cần hoàn. Chỉ tài khoản có `payment.refund` được chọn hoàn và bắt buộc liên kết khoản thu gốc; không vượt tiền còn có thể hoàn hoặc số dư của đơn. RECEPTIONIST mặc định không có quyền này, cần chuyển người được cấp quyền xử lý. Vẫn có thể trả phòng và để khoản cần hoàn được theo dõi trong công nợ.
- Khi trả phòng, các đêm đã sử dụng được chốt STAYED, đêm chưa dùng được RELEASED và giảm trừ theo chính sách đã chấp nhận. Phân đoạn ở và khách được đóng, phòng chuyển DIRTY và tạo nhiệm vụ PENDING. Lịch sử đêm/phòng và bút toán cũ được giữ nguyên.
- Có thể chọn phát hành và tải PDF trong cùng lần xác nhận. Chỉ phát hành hóa đơn toàn đơn khi mọi dòng phòng đã kết thúc. Nếu phát hành PDF thất bại, cả giao dịch trả phòng được hoàn tác. Hóa đơn chụp các khoản POSTED, có QR định danh và ô ký khách/đại diện khách sạn; đây là chứng từ nội bộ, ô ký chưa được ký số. Link tải lại luôn có trên thông báo hoàn tất và chi tiết hóa đơn.

Các số liệu được kiểm tra trước và sau thao tác. Nếu booking, khoản thu, dịch vụ hoặc mức phụ phí đã thay đổi so với bản xem trước, hệ thống yêu cầu xem lại bảng kê. Trả nhiều phòng là một giao dịch: thiếu tiền hoặc lỗi ở bất kỳ phòng nào sẽ hoàn tác cả nhóm. Gửi lại một nhóm đã trả thành công không ghi thu/hoàn hoặc tạo nhiệm vụ lần nữa.

## Phụ phí và chính sách

`EARLY_CHECKIN` và `LATE_CHECKOUT` chọn chính sách hoạt động theo loại phòng, khoảng phút và ưu tiên giảm dần, rồi ID giảm dần. Khoảng phút có đầu bao gồm và cuối loại trừ. Số phút dương được làm tròn lên; `PER_HOUR` tính theo số giờ bắt đầu `ceil(minutes / 60)`, `PERCENT_NIGHT` dùng tiền phòng thuần đã chấp nhận của đêm đầu/cuối, `FIXED` dùng tiền cố định. Modal hiện tên quy tắc, phút, công thức, tiền trước thuế và VAT.

Thuế và điều kiện trả sớm tiếp tục theo snapshot chính sách trên đơn để không thay đổi thỏa thuận của khách khi cấu hình khách sạn đổi. Lưu trú trong ngày tính tối thiểu một đêm theo dịch vụ lõi hiện có. Miễn/giảm phụ phí tại quầy chưa được mở vì RBAC chưa có quyền riêng cho thao tác này; ghi chú không tự miễn phí.

## QR đặt phòng

Trong kết quả tra cứu, tài khoản có `booking.qr.scan` có thể tạo QR cho đơn còn hoạt động. QR chỉ chứa token ngẫu nhiên 256 bit, cơ sở dữ liệu chỉ lưu SHA-256 của token. Token hiệu lực 24 giờ; tạo mã mới thu hồi mã cũ. Quét chỉ tra cứu, không tự xác nhận, nhận phòng hoặc thu tiền.

Dùng máy quét USB nhập payload vào ô tìm kiếm, dán payload hoặc chọn ảnh PNG/JPG tối đa 5 MB. Ảnh được giải mã tại backend và không lưu vào uploads. Ảnh có giới hạn số điểm ảnh và định dạng. Endpoint kiểm tra quyền, hết hạn/thu hồi, ghi thời điểm quét và audit không chứa token/giấy tờ. Chưa có luồng camera trực tiếp. QR hóa đơn chỉ là định danh hóa đơn, không phải token nhận phòng hoặc QR chuyển khoản.

Mã hóa/giải mã QR dùng [ZXing Core 3.5.4](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4), chạy tại backend, không gửi dữ liệu qua dịch vụ QR bên ngoài.

## Buồng phòng

Trang Nhận phòng & Trả phòng có hàng đợi tối đa 50 nhiệm vụ chưa kết thúc theo ngày tạo. Tài khoản có `housekeeping.manage` và `room.status.update` thực hiện PENDING → IN_PROGRESS → COMPLETED → VERIFIED, luôn nhập lý do. Nghiệm thu ghi người và thời điểm, chuyển phòng sang AVAILABLE khi không còn nhiệm vụ mở. Mọi bước giữ khóa phòng, không xử lý phòng có khách hoặc bảo trì hiện hành và ghi audit/lịch sử.

Gửi yêu cầu ưu tiên dùng lại nhiệm vụ đang mở thay vì tạo trùng. Việc đổi trạng thái phòng thủ công sang AVAILABLE cũng bị chặn khi còn nhiệm vụ chưa nghiệm thu. Phòng mới đã sẵn sàng không bắt buộc tạo nhiệm vụ dọn giả.

## Endpoint CMS

| Endpoint | Quyền chính |
|---|---|
| GET `/admin/receptionist/stays` | `booking.read.any` |
| POST `/admin/receptionist/stays/lookup` | `booking.read.any`; thêm `customer.read` cho giấy tờ, `booking.qr.scan` cho QR |
| POST `/admin/receptionist/stays/qr-image` | `booking.read.any`, `booking.qr.scan` |
| POST `/admin/receptionist/stays/{id}/qr` | `booking.read.any`, `booking.qr.scan` |
| GET `/admin/bookings/{id}/checkin-preview?line=...` | `booking.read.any`, `booking.checkin` |
| GET `/admin/bookings/{id}/checkout-preview?line=...&line=...` | `booking.read.any`, `booking.checkout` |
| POST `/admin/receptionist/stays/{id}/checkin` | `booking.checkin`; thêm `payment.collect` khi thu, `booking.update.any` khi đổi phòng |
| POST `/admin/receptionist/stays/{id}/checkout` | `booking.checkout`; quyền thu/hoàn/hóa đơn được kiểm tra riêng |
| POST `/admin/receptionist/stays/priority` | `booking.checkin`, `housekeeping.manage` |
| POST `/admin/receptionist/stays/clean` | `housekeeping.manage`, `room.status.update` |
| POST `/admin/bookings/{id}/invoices/issue` | `invoice.manage` |
| GET `/admin/invoices/{id}/pdf` | `invoice.manage` |

Các endpoint JSON trả `Cache-Control: no-store`; mọi POST cần CSRF. Endpoint form cũ vẫn được giữ, bổ sung trường `dateOfBirth` và `nationality` theo từng khách. Không tự mở rộng quyền hoàn tiền cho RECEPTIONIST. Chuyển khoản tại quầy là ghi nhận tiền đã kiểm tra; tích hợp ngân hàng, gửi email hóa đơn và chữ ký số cần cấu hình/dịch vụ riêng.

## Kiểm thử

`ReceptionStayWorkflowIntegrationTest` kiểm tra phụ phí theo giờ và phần trăm/thuế, mã hóa và che giấy tờ, ngày sinh trẻ em, đổi phòng sạch nguyên tử, phòng bảo trì/thiếu claims, chặn phòng chưa nghiệm thu, chu trình dọn phòng, trả đoàn có hoàn tác khi thiếu tiền, tiền mặt gắn ca, quyền hoàn tiền và khoản thu gốc, trả sớm nhả đêm, chống thao tác lặp, số liệu xem trước bị cũ, QR ảnh/hash/hết hạn/thu hồi, CSRF, HTTP JSON và render HTML. PDF được trích xuất tiếng Việt, giải mã QR và render PNG để xem bố cục.

Chạy `mvnw.cmd -o test`. Khi chạy MySQL phải dùng schema kiểm thử riêng vì fixture tự dọn dữ liệu. Cấu hình `CUSTOMER_DOCUMENT_ENCRYPTION_KEY` và thư mục hóa đơn theo tài liệu dự án. Biên dịch lần đầu cần tải dependency ZXing từ Maven Central.

Kiểm chứng ngày 08/11/2026: suite H2 có 133 bài qua, một bài MySQL opt-in bỏ qua theo cấu hình. Bộ riêng trên MySQL đạt 36/36 bài cho đặt phòng, Dashboard và thủ tục lưu trú. PDF đã được render thành PNG, xem bố cục và giải mã QR. Kiểm tra trực quan CMS qua Windows bị công cụ chặn vì không xác định chắc URL Chrome; phần HTML và luồng JSON/tải PDF đã được kiểm thử tự động.
