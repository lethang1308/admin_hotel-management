# CMS Admin · Đặt phòng và vận hành

Mở **Đặt phòng & Vận hành** trong sidebar CMS. Các quyền của ADMIN và RECEPTIONIST lấy từ RBAC hiện có. Mọi thay đổi dùng POST, kiểm tra CSRF và ghi nhật ký theo quyền.

## Luồng tại quầy

1. Tạo loại phòng, tầng và phòng vật lý trong mô-đun quản lý phòng. Thiết lập giá, thuế và chính sách cọc/hủy trong mô-đun giá.
2. Vào `/admin/bookings/create`. Chọn khách có sẵn bằng mã, tên, SĐT hoặc email, hoặc nhập khách mới. Chọn nguồn điện thoại hoặc khách đến trực tiếp, thêm từng dòng phòng, nhập voucher nếu có và **Xem báo giá**.
3. Chọn **Tạo đơn và giữ chỗ**. CMS tính giá lại và tìm phòng vật lý liên tục cho từng dòng. Giữ chỗ hết hạn theo cấu hình khách sạn, rút ngắn nếu hạn cọc đến trước. Job định kỳ nhả phòng và lượt voucher hết hạn.
4. Ghi nhận khoản cọc nếu có, rồi xác nhận. Để thu hoặc hoàn tiền mặt, mở ca tại `/admin/payments` bằng tài khoản có hồ sơ nhân viên. Với chuyển khoản, chỉ ghi nhận sau khi đã kiểm tra khoản tiền.
5. Vào ngày đến, nhập danh sách khách đúng sức chứa và giấy tờ người lớn, sau đó nhận từng phòng. Số giấy tờ cần khóa mã hóa của dự án. Thêm dịch vụ cho phòng đang ở. Khi trả phòng, hệ thống chốt đêm đã sử dụng, nhả đêm chưa dùng và tạo việc dọn phòng.
6. Chốt công nợ trước khi trả phòng cuối. Khoản thu trước dự kiến phụ phí lúc đang ở cần lý do. Công nợ có dấu âm xuất hiện trong danh sách cần hoàn. Hoàn tiền phải liên kết khoản thu gốc và có quyền `payment.refund`.
7. Khi mọi dòng phòng đã kết thúc, phát hành hóa đơn nội bộ và tải PDF. Nếu cần sửa, hủy hiệu lực hóa đơn, điều chỉnh khoản phải thu trong luồng cho phép và phát hành bản tiếp theo.

## Màn hình

| Đường dẫn | Công việc |
|---|---|
| `/admin/bookings` | Tìm đơn theo mã, khách, SĐT, email, ngày, loại phòng, trạng thái và nguồn |
| `/admin/bookings/{id}` | Chi tiết phòng, đêm, khách, tiền, dịch vụ, lịch sử và thao tác |
| `/admin/bookings/calendar` | Lịch ngày/tuần/tháng theo khóa phòng từng đêm, kèm tồn theo loại |
| `/admin/cancellations` | Hủy, vắng mặt, hết hạn, phí, tiền dự kiến hoàn và người xử lý |
| `/admin/services` | Danh mục, nhóm dịch vụ, bảng bán chạy theo kỳ |
| `/admin/payments` | Giao dịch thu/hoàn, mở và chốt ca tiền mặt |
| `/admin/debts` | Số còn phải thu và tiền có thể hoàn |
| `/admin/invoices` | Tra cứu hóa đơn, xem dòng chi phí và tải PDF |
| `/admin/inventory/reconcile` | Đối chiếu bộ đếm với đêm phòng và khóa phòng, dành cho ADMIN |

## Quy tắc nghiệp vụ

- Ngày đi là cận cuối loại trừ. Ngày kinh doanh theo múi giờ khách sạn. Mốc thời gian trong cơ sở dữ liệu là UTC.
- Giá ban đầu dùng bộ tính giá mô-đun 3. Ngày được giữ nguyên giữ giá chụp cũ. Đêm mới hoặc đêm nâng hạng dùng giá hiện hành và thuế theo đơn gốc, voucher cũ chỉ giữ trên đêm được giữ nguyên. Phí điều chỉnh áp dụng chính sách hủy đã chụp cho ngày bị bỏ. Yêu cầu phải được duyệt trên chênh lệch đã xem trước. Khi đơn hoặc chênh lệch đã thay đổi, cần lập yêu cầu mới.
- Hủy giữ chỗ trước xác nhận và hết hạn không tính phí. Hủy sau xác nhận hoặc vắng mặt dùng chính sách lưu trên đơn. Hủy toàn bộ trước khi sử dụng phòng giải phóng lượt voucher. Hủy một phần giữ lượt voucher của đơn.
- Tồn theo loại phòng và khóa phòng vật lý cập nhật cùng giao dịch. Khóa khách sạn có sẵn được lấy trước để đồng bộ với bảo trì, sửa cấu hình phòng và giá. Khóa voucher, booking, tồn kho theo ngày/loại, phòng theo ID và khoản thu gốc được áp dụng tại các bước cần thiết. PK khóa phòng theo đêm bảo vệ ở cấp DB. Màn hình đối soát chỉ đọc.
- Khoản cọc nằm trong thu ròng. Công nợ = khoản ghi sổ − (thu thành công − hoàn thành công). Giao dịch tại quầy ghi nhận tiền mặt hoặc chuyển khoản đã thực hiện, webhook và cổng thanh toán nằm ở giai đoạn tích hợp sau.
- Hóa đơn chụp từng khoản POSTED. Tổng hóa đơn gồm thuế, tiền thu và công nợ hiển thị riêng. PDF tiếng Việt dùng OpenHTMLtoPDF và font Noto Sans, lưu ở thư mục riêng `uploads/invoices-private` theo mặc định. Có thể đổi bằng `APP_INVOICE_DIRECTORY`. Sao lưu thư mục này cùng dữ liệu ứng dụng. PDF tải qua CMS với quyền `invoice.manage`.

## Cơ sở dữ liệu và kiểm thử

Flyway V6 thêm phiên bản đêm phòng và khóa chống lặp yêu cầu dịch vụ. Vẫn sử dụng 59 bảng hiện có. Khởi động ứng dụng để Flyway áp dụng migration vào database đã cấu hình. Bộ test CMS chạy trên H2 mặc định, có bài kiểm tra hai luồng đặt phòng cuối cùng và kiểm tra trích xuất tiếng Việt từ PDF. Có thể chạy bộ CMS trên schema MySQL thử nghiệm riêng bằng cách đổi datasource của profile test, dùng URL MySQL có `connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true`. Các bài kiểm thử tự dọn dữ liệu kiểm thử, vì vậy phải dùng schema riêng.

```powershell
.\mvnw.cmd clean test
```
