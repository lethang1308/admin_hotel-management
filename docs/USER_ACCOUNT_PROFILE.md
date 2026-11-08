# Role USER — Tài khoản và hồ sơ

BE: Spring Boot tại `C:\laragon\www\hotel-management\backend`.
FE: React/Vite tại `D:\9-project\hotel-management`.

## Khởi động

1. Chạy MySQL rồi khởi động lại BE bằng IDE hoặc `.\mvnw.cmd spring-boot:run`.
2. Flyway tự áp dụng V9: thêm `customers.gender` (MALE/FEMALE/OTHER hoặc NULL). Không cần xóa hay tạo lại database.
3. Tại FE chạy `npm install` nếu thiếu dependency, sau đó `npm run dev`. Khởi động lại Vite sau khi thay đổi .env.
4. Mở `http://localhost:5173/signup`, `/login`, `/profile`, `/forgot-password`.

FE .env đã được cấu hình:
```dotenv
VITE_APP_NAME="StayNest - Luxury Hotel & Booking"
VITE_API_BASE_URL="http://localhost:8080/api/v1"
VITE_ENABLE_HOTEL_API="true"
```

BE .env cần có origin FE trong `CORS_ALLOWED_ORIGINS` và `FRONTEND_URL=http://localhost:5173`.
CORS mặc định hỗ trợ localhost:5173, localhost:3000, 127.0.0.1:5173 và allow-credentials.
Nếu đổi cổng FE, cập nhật cả CORS và FRONTEND_URL. Không đưa bí mật SMTP/JWT vào biến VITE_*.

## API

Prefix: `/api/v1`. Kết quả thành công dùng envelope `ApiResponse.data`; lỗi dùng ProblemDetail.
API riêng tư yêu cầu `Authorization: Bearer <accessToken>` và quyền của tài khoản.

| Method | Route | Payload / kết quả |
| --- | --- | --- |
| POST | /auth/register | fullName, email, phone, password → HTTP 201, user + accessToken + refreshToken + expiresIn |
| POST | /auth/login | email, password → cùng cấu trúc token |
| POST | /auth/refresh | refreshToken → token mới; token cũ bị xoay vòng |
| POST | /auth/logout | refreshToken → thu hồi toàn bộ family của phiên đó |
| GET | /auth/me | tài khoản hiện tại, gồm phone và avatarUrl |
| POST | /auth/forgot-password | email → thông báo chung, kể cả email không tồn tại |
| POST | /auth/reset-password | token, newPassword |
| PUT | /auth/change-password | currentPassword, newPassword |
| GET | /users/profile | hồ sơ cá nhân đang đăng nhập |
| PUT | /users/profile | fullName, phone, dateOfBirth, gender, address |
| POST | /users/profile/avatar | multipart/form-data, field file → avatarUrl |
| GET | /notifications?page=0&size=10 | data.items, totalElements và các thuộc tính phân trang |
| GET | /notifications/unread-count | data.count |
| PATCH | /notifications/{id}/read | đánh dấu một thông báo của chính mình |
| PATCH | /notifications/read-all | đánh dấu tất cả thông báo của chính mình |

Ví dụ cập nhật hồ sơ:
```json
{
  "fullName": "Nguyễn Văn An",
  "phone": "0912345678",
  "dateOfBirth": "1995-10-20",
  "gender": "MALE",
  "address": "Hà Nội"
}
```

- Đăng ký công khai chỉ cấp USER; không nhận quyền Admin từ FE.
- SĐT bắt buộc, định dạng Việt Nam 0/+84; chuẩn hóa +84 thành 0, chặn trùng cả hai cách viết. Email trùng/SĐT trùng trả 409.
- Đồng bộ họ tên/SĐT giữa users và customers trong cùng transaction. Email, vai trò, giấy tờ và ghi chú nội bộ không thể sửa qua endpoint profile.
- Ngày sinh phải ở quá khứ. Trường tùy chọn có thể gửi null để xóa.
- Avatar nhận JPG/PNG/WebP thật, tối đa 5 MB và 16 triệu điểm ảnh. Server đọc nội dung ảnh, thu nhỏ tối đa 1024 px và mã hóa lại thành PNG để bỏ metadata. Tên file ngẫu nhiên; không dùng tên file người dùng.
- Avatar lưu tại `uploads/hotel/avatars` theo cấu hình media hiện có; URL công khai `/media/hotel/avatars/...`. FE tự ghép origin BE. File cũ được giữ để URL cache còn hoạt động.
- Mật khẩu ít nhất 8 ký tự, có chữ và số, tối đa 72 byte UTF-8. Đổi/reset phải dùng mật khẩu khác mật khẩu hiện tại.
- Đổi/reset tăng security_version, thu hồi access token cũ khi xác thực lại, toàn bộ refresh token và phiên CMS liên quan. FE yêu cầu đăng nhập lại.
- Logout xóa phiên FE ngay và thu hồi refresh family; JWT access đã phát hành vẫn có thời hạn tối đa theo TTL cấu hình.

## Email quên mật khẩu

Dùng cấu hình SMTP có sẵn của BE, không gửi email mô phỏng từ FE:

```dotenv
GUEST_MAIL_ENABLED=true
GUEST_MAIL_FROM=no-reply@your-domain.example
SMTP_HOST=your-smtp-host
SMTP_PORT=587
SMTP_USERNAME=your-smtp-user
SMTP_PASSWORD=your-smtp-password
SMTP_AUTH=true
SMTP_STARTTLS=true
FRONTEND_URL=http://localhost:5173
```

Điền thông tin SMTP thật của bạn vào BE .env rồi restart BE. Với SMTP bắt mail cục bộ, dùng host/port và AUTH/STARTTLS phù hợp của công cụ đó.

- Token ngẫu nhiên an toàn, lưu SHA-256, hiệu lực 30 phút, dùng một lần.
- Mỗi tài khoản tối đa một yêu cầu mới trong 60 giây; yêu cầu mới sau thời gian này vô hiệu token trước.
- Email HTML chứa link `FRONTEND_URL/reset-password?token=...`; gửi bất đồng bộ sau khi transaction lưu token thành công.
- Phản hồi luôn dùng thông báo chung, không tiết lộ email tồn tại hay trạng thái tài khoản.
- Không ghi token thô vào log. Khi SMTP chưa cấu hình/tắt hoặc gửi thất bại, BE ghi cảnh báo chung; API không tuyên bố đã gửi thành công tới một tài khoản cụ thể.
- Hàng chờ reset email ở bộ nhớ, không có retry bền vững qua lần restart. Khi gửi thất bại có thể yêu cầu lại sau 60 giây. Hàng đợi email booking CMS hiện có vẫn giữ cơ chế riêng.

## Thông báo và phiên FE

- Tự tạo thông báo khi có BOOKING_CREATED/CONFIRMED/CANCELLED/CHANGED/NO_SHOW, PAYMENT_COLLECTED/REFUNDED cho customer có user_id.
- Thông báo thanh toán có số tiền và mã booking; sự kiện được chống trùng bằng deduplication_key.
- Chuông khách hàng chỉ đọc nhóm BOOKING_*/PAYMENT_*; không lộ hàng chờ lễ tân và không nhân đôi lịch sử email GUEST_*.
- Mọi truy vấn/đánh dấu đọc đều giới hạn user_id từ JWT. Đọc ID của người khác trả 404.
- Chuông tải 10 thông báo gần nhất, kiểm tra lại mỗi 30 giây, khi focus hoặc mở; API hỗ trợ phân trang.
- AuthContext, axios và fetch dùng chung session/token. Các request 401 đồng thời chỉ refresh một lần; hỗ trợ phối hợp tab qua Web Locks.
- Logout hoặc chuyển tài khoản trong lúc refresh không khôi phục phiên cũ và không retry request cũ bằng tài khoản mới.
- Lỗi mạng/login không tạo token giả hoặc mặc định quyền quản trị.
- Dữ liệu tài khoản luôn gọi API thật. Các phân hệ tìm phòng/đặt phòng của FE tiếp tục được ghép ở các module User sau; không coi dữ liệu demo của các phân hệ đó là kết quả BE.

## Hướng dẫn test UI

Dùng tài khoản/dữ liệu thử nghiệm của bạn:

1. /signup: nhập họ tên, email mới, SĐT, mật khẩu mạnh và xác nhận. Tự thao tác chấp nhận điều khoản; đăng ký thành công phải tự đăng nhập, hiện đúng tên, không có quyền quản trị.
2. /profile → Edit Profile: đổi tên, SĐT, ngày sinh, giới tính, địa chỉ; lưu, tải lại trang, kiểm tra dữ liệu vẫn đúng. Thử SĐT trùng của tài khoản khác → 409, dữ liệu cũ không bị ghi đè.
3. Bấm camera trên avatar, chọn JPG/PNG/WebP dưới 5 MB; ảnh trên profile và menu tài khoản phải cập nhật. File không phải ảnh phải bị từ chối.
4. Bảo mật tài khoản → Đổi mật khẩu: thử mật khẩu hiện tại sai; sau đó tự nhập mật khẩu mới hợp lệ. Thành công quay về đăng nhập; mật khẩu cũ không dùng được.
5. /forgot-password: yêu cầu link, mở email SMTP thử nghiệm, tự nhập mật khẩu mới tại link. Dùng lại link sau thành công phải thất bại. Email không tồn tại vẫn có thông báo chung.
6. Tạo booking thật cho customer liên kết với user qua CMS, xác nhận và ghi nhận thanh toán. Đăng nhập user đó, mở chuông, kiểm tra mã booking/số tiền và bộ đếm; đánh dấu đọc/tất cả. User khác không thấy thông báo đó.
7. Đăng xuất, tải lại /profile phải được yêu cầu đăng nhập; lỗi BE không được chuyển sang tài khoản mẫu.

## Kiểm thử tự động

BE:
```powershell
.\mvnw.cmd test
.\mvnw.cmd "-Dtest=UserAccountIntegrationTest,AuthAndRbacIntegrationTest,HotelSchemaMigrationTest" test
```

FE:
```powershell
npm run test:account
npm run build
```

UserAccountIntegrationTest kiểm tra đăng ký, quyền sở hữu, đồng bộ profile, avatar PNG/WebP, CORS, reset token/email MIME, dùng token đồng thời, thu hồi phiên và thông báo booking/thanh toán. Bộ test dùng SMTP mock, không gửi email ra ngoài.

Trong lần triển khai này, kiểm tra trình duyệt tự động bị công cụ dừng do không xác định URL Windows đủ tin cậy; không coi các bước UI ở trên đã được xác minh trực quan toàn bộ. Dùng kết quả API/build và hướng dẫn này để test UI thủ công.

## Kết quả xác minh ngày 08/11/2026

- MySQL 8: 23/23 kiểm thử tài khoản, RBAC và migration đạt; bao gồm reset token đồng thời và giới hạn yêu cầu email đồng thời.
- H2: 22 kiểm thử tài khoản/RBAC/schema đạt, 1 bài MySQL opt-in được bỏ qua đúng cấu hình.
- Các bài hồi quy CMS tài khoản, thu ngân và chăm sóc phòng trên MySQL: 41/41 đạt.
- FE: 10/10 kiểm tra API client/phiên đăng nhập đạt; lint các file JS/JSX thay đổi không có lỗi; production build thành công.
- HTTP trên server thử nghiệm riêng: 15/15 kiểm tra đạt, gồm lưu hồ sơ, WebP thành PNG, multipart quá 5 MB trả 413, ảnh giả trả 400, CORS và thu hồi refresh khi logout.
- BE biên dịch thành công. Database dự án đã có V9 thành công; API đang chạy ở cổng 8080 đã công bố các đường dẫn mới.
- SMTP thực tế hiện chưa cấu hình và đang tắt. Test email dùng JavaMailSender mock để kiểm tra nội dung/link, không gửi thư tới người thật.

Mã nguồn và pom.xml của bản BE kiểm thử tách biệt đã được đối chiếu trùng với bản trong dự án.
