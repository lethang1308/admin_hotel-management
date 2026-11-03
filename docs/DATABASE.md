# Database quản lý khách sạn

Schema được triển khai từ **docs/database.txt**: 59 bảng ứng dụng, 125 khóa ngoại và 80 CHECK constraints sau V4. Database có thêm bảng nội bộ `flyway_schema_history` của Flyway, tổng cộng 60 bảng sau khi migrate.

## Migration

| Phiên bản | File | Nội dung |
|---|---|---|
| V1 | `V1__init_base_schema.sql` | 4 bảng nền: users, roles, user_roles, refresh_tokens; giữ nguyên file đã có |
| V2 | `V2__create_hotel_schema.sql` | Bổ sung cột hồ sơ cho users, thêm 55 bảng nghiệp vụ cùng khóa/ràng buộc/index theo thiết kế |
| V3 | `V3__seed_hotel_roles_and_permissions.sql` | Bổ sung vai trò RECEPTIONIST, 45 permission và phân bổ quyền cho ADMIN/RECEPTIONIST/USER |
| V4 | `V4__cms_identity_and_account_security.sql` | Security version cho thu hồi session/JWT; giấy tờ khách mã hóa; backfill hồ sơ tài khoản cũ |

Các file nằm trong `src/main/resources/db/migration`. Chạy backend bình thường, Flyway tự áp dụng các phiên bản chưa chạy. Hibernate tiếp tục dùng `ddl-auto: validate`.

```powershell
# Chạy tại C:\laragon\www\hotel-management\backend
.\mvnw.cmd spring-boot:run
```

Không import lại bản SQL CREATE toàn bộ database vào schema đã có V1; không sửa checksum/file V1 và không bật `ddl-auto=create`. Migration V2 giữ các ID, email, password hash, trạng thái, quan hệ vai trò và refresh token cũ. Không thêm tài khoản mẫu hoặc booking giả.

`users.status` hỗ trợ `ACTIVE`, `LOCKED`, `DISABLED`, đồng thời giữ `INACTIVE` để tương thích code/UI/dữ liệu đang có. `UserStatus` đã được bổ sung DISABLED; mọi trạng thái khác ACTIVE vẫn bị từ chối đăng nhập theo logic hiện tại.

Các cột users được bổ sung: `phone` (UNIQUE, cho phép NULL), `avatar_url`, `email_verified_at`, `last_login_at`, `locked_until`, `password_changed_at`. Tài khoản cũ không cần dữ liệu giả để backfill các cột này.

## Sáu nhóm bảng

1. Tài khoản/phân quyền/hồ sơ: 10 bảng.
2. Khách sạn/phòng/vận hành: 13 bảng.
3. Giá/chính sách/voucher: 8 bảng.
4. Đặt phòng/giữ chỗ/lưu trú: 13 bảng.
5. Dịch vụ/công nợ/thanh toán/hóa đơn: 8 bảng.
6. Thông báo/yêu thích/đánh giá/hỗ trợ: 7 bảng.

Tên bảng và toàn bộ cột đối chiếu trực tiếp với `docs/database.txt` trong test `HotelSchemaMigrationTest`. Đây là lớp lưu trữ cho các module nghiệp vụ. CMS tài khoản đã nạp role và permission vào Spring Security authorities, kiểm tra ở controller/service và menu. API quản lý tài khoản hiện có yêu cầu thêm account.manage. Các API nghiệp vụ khách sạn tiếp theo vẫn cần kiểm tra quyền và sở hữu tương ứng. Xem CMS_ADMIN.md để sử dụng mô-đun tài khoản.

Không seed giá phòng, thông tin khách sạn hay chính sách tiền cọc/hủy giả định. Khi triển khai module: tạo `deposit_policies` và `cancellation_policies` trước, rồi cấu hình một dòng `hotel_settings` id=1 trỏ tới các chính sách mặc định đó.

## Kiểm tra

```powershell
.\mvnw.cmd test
```

Test mặc định dùng H2 MySQL mode, không đọc/ghi database MySQL của ứng dụng.

Cấu hình H2 có `NON_KEYWORDS=VALUE` vì thiết kế có cột `rate_rules.value`, hợp lệ trên MySQL nhưng được H2 coi là từ khóa. Điều chỉnh chỉ áp dụng profile test, không đổi tên cột hoặc sửa migration đã áp dụng. Các trường hợp được kiểm tra:

- Tạo mới đủ bảng và cột theo file thiết kế, kiểm tra số lượng FK/CHECK.
- Nâng cấp từ V1 có tài khoản INACTIVE, vai trò và refresh token, xác minh dữ liệu được giữ nguyên.
- Kiểm tra DISABLED hợp lệ, trạng thái không hợp lệ bị chặn.
- Chặn tồn kho vượt năng lực, khóa chính trùng và ảnh không có loại phòng cha.
- Kiểm tra seed đủ vai trò/quyền; USER không có quyền hoàn tiền/sửa giá/xem doanh thu.
- Chạy migrate lần hai không thực thi lại migration.

Test MySQL là tùy chọn, mặc định được bỏ qua. Để chạy, chuẩn bị **database thử nghiệm trống riêng**, tên bắt đầu `hotel_migration_test_`, cấu hình các biến `HOTEL_MYSQL_TEST_URL`, `HOTEL_MYSQL_TEST_USER`, `HOTEL_MYSQL_TEST_PASSWORD`, rồi chạy:

```powershell
.\mvnw.cmd test -Dtest=HotelSchemaMigrationTest
```

Test MySQL có kiểm tra URL localhost và tên database thử nghiệm; không cho dùng tên database ứng dụng. Ví dụ URL: `jdbc:mysql://127.0.0.1:3306/hotel_migration_test_manual?serverTimezone=UTC`.

## Quy tắc cần triển khai ở service

UNIQUE/CHECK/FK đã được tạo nhưng các thao tác liên bảng vẫn cần transaction. Đặc biệt: khóa tồn kho theo loại/ngày khi đặt; tạo room_night_claims cho từng đêm; điều chỉnh lịch sử và claims khi đổi phòng/bảo trì; kiểm tra sức chứa; chụp giá từng đêm; kiểm tra quota voucher; khóa khoản thu gốc khi hoàn tiền; chống xử lý webhook lặp; tổng hợp trạng thái đơn nhiều phòng; chỉ phát hành một hóa đơn hiệu lực.

Không xóa cứng lịch sử đặt phòng/tài chính. Dữ liệu tiền dùng DECIMAL; timestamp nghiệp vụ dùng UTC, ngày đêm phòng dùng Asia/Ho_Chi_Minh. Tài liệu thiết kế chi tiết trước đó nằm ở `../docs/database/README.vi.md` tính từ thư mục backend.

CMS tài khoản và phân quyền đã triển khai: [CMS_ADMIN.md](CMS_ADMIN.md). Sau V4 có 618 cột thuộc 59 bảng nghiệp vụ.
