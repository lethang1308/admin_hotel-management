# Lễ tân · Dịch vụ và thanh toán tại quầy

Mở `/admin/receptionist/cashier` trong nhóm **Bàn lễ tân → Dịch vụ & Thu ngân**. Chọn đơn qua tra cứu; nút Dịch vụ & Thu ngân cũng có trên Dashboard, danh sách và chi tiết đặt phòng. Một modal dùng chung hiển thị công nợ, tiến độ cọc, dịch vụ, lịch sử thanh toán, yêu cầu hoàn và hóa đơn.

## Chuẩn bị

- Khởi động lại backend để Flyway chạy **V7__front_desk_payments.sql**. V7 bổ sung bảng `refund_requests`, thông tin ngân hàng nhận tiền, tên công ty trên hóa đơn và metadata ảnh chứng từ. Không sửa/xóa thanh toán, charges hoặc hóa đơn cũ. Schema hiện có 60 bảng; DBML trong `database.txt` đã được cập nhật.
- Người có `hotel.manage` mở phần **Tài khoản ngân hàng nhận chuyển khoản** ở cuối trang thu ngân, nhập BIN ngân hàng 6 số, số tài khoản 6–19 số và tên chủ tài khoản đã xác minh. Để trống cả ba để tắt QR. Cấu hình có kiểm tra phiên bản và audit; không có tài khoản ngân hàng mẫu được tự gán.
- Tiền mặt yêu cầu hồ sơ nhân viên còn làm việc và ca đang mở của chính người ghi thu/hoàn. Mở ca ngay trong modal khi chọn CASH; kết ca ở Dashboard hoặc trang Thanh toán hiện có. Chênh lệch kiểm đếm vẫn bắt buộc ghi lý do.
- Ảnh chứng từ được lưu riêng trong `uploads/payments-private`, có thể đổi qua `app.payment.proof-directory` / `APP_PAYMENT_PROOF_DIRECTORY`. Sao lưu thư mục này cùng database và hóa đơn. Không đặt thư mục chứng từ trong đường dẫn static/public.

## Ghi dịch vụ

1. Chọn dòng phòng đang CHECKED_IN, lọc danh mục hoạt động rồi chọn dịch vụ. Nhập số lượng, hỗ trợ ba chữ số thập phân cho kg/giờ. Nút tăng/giảm thay đổi một đơn vị; có thể nhập trực tiếp 1.500 kg.
2. Modal hiện đơn vị, đơn giá, VAT và tổng. Khi gửi, server kiểm tra lại giá/thuế đã xem. Giá thay đổi cần tải lại để duyệt giá mới. Mã chống lặp gắn với từng lần thêm, tránh ghi hai lần khi gửi lại.
3. Bản ghi giữ snapshot tên, đơn vị, số lượng, đơn giá và thuế; gắn phân đoạn phòng đang ở và nhân viên thực hiện. Giá danh mục thay đổi không sửa các lần sử dụng cũ.
4. Tiền dịch vụ được làm tròn đến đồng trước VAT. Nếu tích số lượng × đơn giá tạo phần lẻ, sổ cái giữ dòng SERVICE gốc và thêm ADJUSTMENT làm tròn, sau đó tính TAX. Cách này giữ đúng constraint số lượng/đơn giá và tránh công nợ lẻ không thể quyết toán bằng VND nguyên đồng.
5. Bảng kê có tên dịch vụ, phòng, số lượng/đơn vị, giá, thuế, tổng, thời gian, người ghi và ghi chú. Nhập sai thì **Hủy bản ghi** kèm lý do tối đa 500 ký tự, rồi ghi lại dòng đúng. Hủy chuyển usage và các charges liên quan sang VOIDED, tính lại nợ; không xóa lịch sử. Hóa đơn ISSUED chặn thêm/hủy charges.

## Cọc và thanh toán nhiều đợt

Thanh tóm tắt lấy từ sổ cái: tổng khoản POSTED; thu SUCCEEDED; hoàn SUCCEEDED; thực thu = thu − hoàn; công nợ = tổng − thực thu; tiền cần hoàn = max(0, −công nợ). Công nợ dương viền đỏ, bằng không viền xanh, âm viền vàng. Số liệu cập nhật sau thao tác, qua tín hiệu SSE và đối chiếu lại mỗi 20 giây khi modal đang mở. Các ô đang nhập được giữ nguyên.

**Đặt cọc:** chỉ ghi DEPOSIT cho đơn PENDING còn hạn giữ chỗ. Mức tối thiểu = min(cọc đã chấp nhận, tổng chi phí hiện tại). Cọc còn thiếu trừ thực thu, không âm. Thanh tiến độ và nhãn Đã cọc đủ giúp nhận biết đơn sẵn sàng xác nhận; ghi cọc không tự xác nhận booking. Hết hạn giữ chỗ chặn thu cọc tại server.

**Thanh toán:** mỗi đợt là một giao dịch PAYMENT riêng. Có thể thu một phần hoặc bấm Thu đủ toàn bộ nợ. Khi đang CHECKED_IN, được ghi tạm ứng vượt nợ hiện tại nếu có lý do; các trạng thái khác giữ giới hạn của dịch vụ lõi. Mọi khoản thu kiểm tra số liệu đã xem trước khi ghi. Gửi lại cùng khóa và cùng nội dung trả giao dịch cũ, không thu thêm.

**CASH:** server khóa hồ sơ nhân viên và lấy ca đang mở, gắn `work_session_id`. Số tiền kỳ vọng = đầu ca + thu CASH thành công − hoàn CASH thành công. Thiếu ca/hồ sơ sẽ không ghi tiền.

**BANK_TRANSFER:** nhập mã tham chiếu và có thể đính kèm ảnh PNG/JPG tối đa 5 MB. Tham chiếu lưu ở `provider_transaction_id`, dùng unique constraint có sẵn để chặn ghi lại cùng tham chiếu. Ảnh được kiểm tra định dạng và kích thước điểm ảnh, mã hóa lại để bỏ metadata, lưu với tên ngẫu nhiên trong thư mục riêng. Lỗi ảnh hoàn tác cả khoản thu và outbox; file mới được dọn khi transaction rollback. Link xem ảnh yêu cầu đăng nhập, `booking.read.any` và quyền thu hoặc hoàn; không lộ đường dẫn lưu trong snapshot.

QR chuyển khoản được tạo tại backend, không gọi API QR bên ngoài và không cần API key. BIN/tài khoản, số tiền và nội dung đều hiện cạnh ảnh để đối chiếu. Do nội dung QR giới hạn 25 ký tự, mã booking dài hơn được dùng 25 ký tự đầu; mã đầy đủ vẫn có trên đơn. Tham số và mẫu đối chiếu dựa trên [tài liệu VietQR của Casso](https://www.vietqr.io/generate). QR chỉ hỗ trợ khách chuyển tiền, không chứng minh đã nhận tiền; lễ tân ghi thu sau khi kiểm tra khoản thực nhận. Chưa có webhook xác nhận ngân hàng trong mô-đun này.

## Hoàn tiền và phê duyệt

- Tài khoản có `payment.refund` được ghi nhận hoàn trực tiếp. Chọn khoản thu gốc SUCCEEDED, phương thức hoàn, số tiền và lý do 10–500 ký tự. Tiền hoàn không vượt phần chưa hoàn của giao dịch gốc và không vượt tiền cần hoàn của booking.
- Lễ tân mặc định không có `payment.refund`: nút chuyển thành **Gửi yêu cầu hoàn tiền**. Yêu cầu lưu bền, chưa làm thay đổi tiền và chưa giữ chỗ tiền hoàn. Người tạo xem/rút được yêu cầu của chính mình khi còn PENDING. Người có quyền hoàn xem hàng đợi chờ toàn khách sạn; tối đa 100 yêu cầu theo thời gian tạo.
- Thông báo yêu cầu mới chỉ gửi các tài khoản vận hành đang hoạt động có `payment.refund`. Chọn thông báo mở đơn ở tab hoàn tiền. Kết quả xử lý gửi riêng người yêu cầu. Không gửi email/Slack.
- **Duyệt & ghi nhận đã hoàn tiền** dùng khi người có quyền đã thực hiện/đối chiếu việc trả tiền cho khách. Hệ thống khóa booking, kiểm tra lại cả hai hạn mức, ghi REFUND liên kết khoản thu gốc, `approved_by`, audit và outbox trong cùng transaction. Không tự gọi ngân hàng để chuyển tiền. Hoàn CASH dùng ca của người thực hiện, không dùng ca của người yêu cầu.
- Yêu cầu hợp lệ có thể thành không hợp lệ khi số dư thay đổi. Khi đó việc duyệt thất bại và yêu cầu vẫn PENDING; người quản lý có thể từ chối kèm lý do. Hai yêu cầu được duyệt đồng thời vẫn bị giới hạn bởi số dư mới nhất. Gửi lại quyết định đã thành công không hoàn lần hai.
- Các trạng thái: PENDING, COMPLETED, REJECTED, WITHDRAWN. Không tự cấp quyền hoàn cho RECEPTIONIST.

## Hóa đơn doanh nghiệp

Tên đơn vị/công ty được lưu riêng trong `buyer_company_name`; người liên hệ và email vẫn giữ trong thông tin người mua. Có thể nhập công ty, MST và địa chỉ ở tab Hóa đơn, form chi tiết booking hoặc lúc check-out nhanh. Chỉ phát hành khi mọi dòng phòng đã kết thúc; hóa đơn đã phát hành giữ nguyên nội dung. Muốn sửa phải hủy hiệu lực có lý do và phát hành revision mới.

PDF có font Noto Sans tiếng Việt, định dạng số theo Việt Nam, tên/địa chỉ/MST công ty, bảng kê, tổng trước thuế/VAT, QR định danh và ô ký khách/đại diện khách sạn. Logo đã upload trong hệ thống được nhúng vào PDF; renderer không tải URL logo từ mạng. Hóa đơn vẫn là chứng từ nội bộ của bài tập, chưa ký số và chưa gửi email tự động.

## Endpoint bổ sung

| Endpoint | Chức năng / quyền |
|---|---|
| GET `/admin/receptionist/cashier` | Trang quầy, booking.read.any |
| GET `/admin/receptionist/cashier/{id}` | Snapshot JSON của đơn, booking.read.any |
| POST `/{id}/service` | Ghi dịch vụ đã xem giá, service.usage.manage |
| POST `/{id}/services/{usage}/void` | Hủy usage, service.usage.manage |
| POST `/{id}/collect` | Multipart ghi thu và ảnh, payment.collect + booking.read.any |
| GET `/{id}/bank-qr?amount=...` | Tạo QR, payment.collect + booking.read.any |
| GET `/proofs/{payment}` | Xem ảnh, booking.read.any + payment.collect/refund |
| POST `/{id}/refund-request` | Gửi yêu cầu, payment.collect + booking.read.any |
| POST `/{id}/refund` | Hoàn trực tiếp, payment.refund |
| POST `/{id}/refund-requests/{request}/decide` | Duyệt/ghi hoàn hoặc từ chối, payment.refund |
| POST `/{id}/refund-requests/{request}/withdraw` | Rút yêu cầu của mình, payment.collect + booking.read.any |
| POST `/{id}/invoice` | Phát hành PDF và công ty, invoice.manage |
| POST `/shift` | Mở/kết ca của mình, payment.collect/refund/report.shift |
| POST `/bank` | Lưu cấu hình nhận chuyển khoản, hotel.manage |

Các đường dẫn rút gọn trong bảng có tiền tố `/admin/receptionist/cashier`. JSON/ảnh trả `Cache-Control: no-store`; mọi POST cần CSRF. Endpoint cũ được giữ. Lỗi JSON/tham số trả 400, thiếu quyền trả 403, trùng tham chiếu trả 409; không gửi SQL hoặc đường dẫn file riêng trong phản hồi.

## Kiểm thử

`ReceptionCashierIntegrationTest` kiểm tra đúng ví dụ 2 lon Coca + 1.5 kg giặt là tăng nợ 110.000đ, void giảm 66.000đ, giá/thuế snapshot, giá đã xem bị cũ, số lượng lẻ và làm tròn VND, thu cọc/thu nhiều đợt, ca tiền mặt, hết hạn giữ chỗ, mã tham chiếu trùng, ảnh sai hoàn tác tiền, ảnh có phân quyền, yêu cầu hoàn và người duyệt, hai hạn mức hoàn, thay đổi số dư sau yêu cầu, quyền sở hữu yêu cầu, hai luồng duyệt đồng thời, QR đối chiếu mẫu công bố, MST/công ty/logo trên PDF và các endpoint HTTP/CSRF.

`HotelSchemaMigrationTest` đối chiếu DBML và migration: 60 bảng, 130 khóa ngoại, 81 check constraints; kiểm tra nâng cấp và giữ tài khoản cũ. Chạy `mvnw.cmd -o test`. MySQL opt-in và các integration test phải dùng schema riêng vì fixture dọn dữ liệu giữa các bài.

Kết quả xác minh ngày 08/11/2026:

- Toàn bộ Maven suite trên H2: **146 bài, 145 đạt, 1 bỏ qua** (bài MySQL opt-in), không lỗi.
- Bộ hồi quy nghiệp vụ lễ tân/đặt phòng/thanh toán và migration trên MySQL 8.0.30 riêng: **51/51 đạt**, gồm nâng cấp schema và kiểm tra hai luồng duyệt hoàn tiền đồng thời. Các schema thử nghiệm được dọn sau khi chạy.
- JavaScript đã kiểm tra cú pháp; giao diện được kiểm tra qua HTML render, HTTP, CSRF và phân quyền. PDF được đọc bằng PDFBox và render thành ảnh để kiểm tra tiếng Việt, logo, công ty/MST, bảng kê và QR. Chưa kiểm tra thao tác giao diện trực tiếp trong trình duyệt.
