# Hotel Management System (Backend)

Backend quản lý khách sạn xây dựng bằng **Spring Boot 4.0 + Java 17**, kiến trúc Modular Monolith:

- **REST API** (`/api/v1/**`) dùng JWT + refresh token cho client React/Vite.
- **Admin Portal** (Thymeleaf, `/admin`) dùng session + CSRF.
- Cơ sở dữ liệu **MySQL 8**, migration tự động bằng **Flyway**.

Các module chính: `auth`, `user`, `role`, `employee`, `customer`, `hotel`, `floor`, `room`, `roomtype`, `amenity`, `audit`.

---

## 1. Yêu cầu cài đặt

| Công cụ | Phiên bản | Kiểm tra |
| :--- | :--- | :--- |
| JDK | 17 | `java -version` |
| MySQL | 8.0+ (có thể dùng Laragon / XAMPP / Docker) | `mysql --version` |
| Git | bất kỳ | `git --version` |

> Không cần cài Maven, dự án đã có Maven Wrapper (`mvnw`, `mvnw.cmd`).

---

## 2. Hướng dẫn chạy dự án

### Bước 1: Clone dự án

```bash
git clone https://github.com/lethang1308/backend.git
cd backend
```

### Bước 2: Tạo database MySQL

Bật MySQL, sau đó chạy:

```sql
CREATE DATABASE IF NOT EXISTS hotel_management
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

### Bước 3: Tạo file `.env`

Sao chép file mẫu rồi chỉnh lại giá trị cho máy của bạn.

Windows (PowerShell):

```powershell
Copy-Item .env.example .env
```

Linux / macOS:

```bash
cp .env.example .env
```

Các biến quan trọng trong `.env`:

```properties
SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=8080

DB_HOST=localhost
DB_PORT=3306
DB_NAME=hotel_management
DB_USERNAME=root
DB_PASSWORD=            # mật khẩu MySQL của bạn

# Khóa ký JWT, tối thiểu 32 ký tự
JWT_SIGNING_KEY=replace-with-at-least-32-character-random-secret-key-for-hmac-sha256

# Tạo sẵn tài khoản ADMIN (chỉ chạy ở profile dev)
APP_SEED_ADMIN_ENABLED=true
ADMIN_SEED_EMAIL=admin@example.com
ADMIN_SEED_PASSWORD=ChangeMe123!
ADMIN_SEED_FULL_NAME=System Administrator

# Khóa mã hóa giấy tờ khách hàng: base64 của 32 byte
CUSTOMER_DOCUMENT_ENCRYPTION_KEY=
```

Tạo `CUSTOMER_DOCUMENT_ENCRYPTION_KEY`:

```bash
openssl rand -base64 32
```

Hoặc bằng PowerShell:

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])
```

> **Lưu ý:** Không commit file `.env` lên Git (đã nằm trong `.gitignore`).

### Bước 4: Chạy ứng dụng

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Linux / macOS:

```bash
chmod +x mvnw
./mvnw spring-boot:run
```

Lần chạy đầu tiên Flyway sẽ tự tạo bảng và dữ liệu mẫu (vai trò, khách sạn, loại phòng, tiện nghi...). Khi log báo `Started HotelmanagementApplication`, ứng dụng đã sẵn sàng.

---

## 3. Các đường dẫn chính

| Chức năng | URL |
| :--- | :--- |
| Trang đăng nhập Admin | http://localhost:8080/admin/login |
| Admin Dashboard | http://localhost:8080/admin |
| Swagger UI (chỉ profile dev) | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health check | http://localhost:8080/actuator/health |
| REST Auth API | http://localhost:8080/api/v1/auth/** |

Đăng nhập Admin bằng `ADMIN_SEED_EMAIL` / `ADMIN_SEED_PASSWORD` đã khai báo trong `.env`.

---

## 4. Các lệnh hữu ích

```powershell
# Chạy toàn bộ test (dùng H2 in-memory, không đụng tới MySQL)
.\mvnw.cmd test

# Build file JAR
.\mvnw.cmd clean package

# Chạy JAR ở môi trường production (bắt buộc cấu hình biến môi trường DB_* và JWT_SIGNING_KEY)
java -jar target\hotelmanagement-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

(Trên Linux/macOS thay `.\mvnw.cmd` bằng `./mvnw`.)

---

## 5. Xử lý lỗi thường gặp

| Lỗi | Cách xử lý |
| :--- | :--- |
| `Access denied for user 'root'` | Kiểm tra `DB_USERNAME`, `DB_PASSWORD` trong `.env`. |
| `Unknown database 'hotel_management'` | Chưa tạo database, làm lại Bước 2. |
| `Communications link failure` | MySQL chưa chạy hoặc sai `DB_HOST` / `DB_PORT`. |
| `CUSTOMER_DOCUMENT_ENCRYPTION_KEY must be a base64 encoded 32-byte key` | Tạo khóa theo hướng dẫn ở Bước 3. |
| `Port 8080 was already in use` | Đổi `SERVER_PORT` trong `.env`. |
| `JAVA_HOME` / sai phiên bản Java | Cài JDK 17 và đặt `JAVA_HOME` trỏ đến JDK đó. |

---

## 6. Tài liệu thêm

Xem thư mục [`docs/`](docs/):

- [CMS_ADMIN.md](docs/CMS_ADMIN.md): trang quản trị Admin
- [CMS_HOTEL_ROOMS.md](docs/CMS_HOTEL_ROOMS.md): quản lý khách sạn, phòng
- [DATABASE.md](docs/DATABASE.md): cấu trúc cơ sở dữ liệu
