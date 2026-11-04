# Share Files: thiết kế

Ngày: 2026-11-04. Chủ repo đã uỷ quyền cho Claude tự chốt các quyết định còn lại ("m tự chốt phương án tự làm đi").

## Mục tiêu

Chuyển file hai chiều giữa điện thoại/tablet và PC trong mạng nhà, bằng app native riêng:

- Thiết bị: Lenovo Legion Y700 Gen 5 (Android 16), iPhone 16 Pro Max, PC Windows 11 (LAN 2.5 Gbps), router ASUS BE6500 (chỉ bật 5 GHz).
- Tốc độ: chạy hết mức đường truyền cho phép, app không tự bóp. Không đặt con số mục tiêu.
- File đi thẳng trong LAN. Không cloud, không tài khoản, không bên thứ 3.
- Không phải gõ IP.
- Giao diện đẹp, nhiều màu.
- iOS: GitHub Actions build IPA chưa ký, chủ repo tự ký bằng ESign.

## Ngoài phạm vi

- PC duyệt ngược file trong điện thoại (iOS không làm server khi nằm nền được).
- Y700 gửi trực tiếp cho iPhone (đi qua PC nếu cần).
- Truy cập từ ngoài mạng nhà, đồng bộ tự động, nhiều người dùng, lên store.

## Kiến trúc

PC là trung tâm. App PC chạy nền ở khay hệ thống, mở HTTPS server. Y700 và iPhone là client.

```
Y700 (Kotlin + Compose) ──┐  WiFi 5 GHz
                          ├── router ──LAN 2.5G── PC (C# + WPF + Kestrel)
iPhone (Swift + SwiftUI) ─┘
```

| Thành phần | Công nghệ | Việc |
|---|---|---|
| `pc/src/ShareFiles.Server` | .NET 10, ASP.NET Core Kestrel | Giao thức, ghép đôi, upload, download, hàng chờ, cấu hình. Không phụ thuộc UI, test được độc lập |
| `pc/src/ShareFiles.App` | WPF, NotifyIcon, QRCoder | Cửa sổ, khay hệ thống, mDNS, firewall, khởi động cùng Windows |
| `android/` | Kotlin, Jetpack Compose, OkHttp, CameraX + ZXing | Client Android, foreground service giữ chuyển file khi nằm nền |
| `ios/` | Swift, SwiftUI, URLSession, Network.framework | Client iOS |
| `protocol/PROTOCOL.md` | Tài liệu | Hợp đồng chung giữa 3 bên |

## Luồng sử dụng

1. **Ghép đôi (1 lần):** PC hiện QR. Điện thoại quét trong app (hoặc bằng camera hệ thống qua deep link `sharefiles://pair`, hoặc dán link). QR chứa fingerprint chứng chỉ, secret dùng 1 lần, địa chỉ gợi ý.
2. **Tự tìm PC:** Bonjour `_sharefiles._tcp`. Nếu không thấy thì thử địa chỉ đã lưu. Không bao giờ phải gõ IP.
3. **Điện thoại → PC:** chọn ảnh hoặc file rồi gửi, PC lưu vào thư mục nhận.
4. **Điện thoại lấy từ PC:** duyệt các thư mục đã share rồi tải về.
5. **PC → điện thoại:** kéo file vào cửa sổ PC, chọn máy nhận. File vào hàng chờ, điện thoại đang mở app thì tự tải (poll mỗi 3 giây), chưa mở thì chờ.

## Quyết định kỹ thuật

- **TLS 1.3 / HTTP/1.1:** HTTP/1.1 vì một luồng TCP trực tiếp có overhead thấp hơn HTTP/2. TLS bảo vệ token và nội dung; các chip đời này có tăng tốc AES phần cứng nên không dự kiến là nút cổ chai. Màn đo tốc độ trong app sẽ kiểm chứng. Không làm công tắc tắt TLS cho tới khi số đo cho thấy cần.
- **Pin chứng chỉ:** PC tự sinh chứng chỉ ECDSA P-256 tự ký. Client so SHA-256 của chứng chỉ với fingerprint lấy từ QR, không dùng CA hay hostname.
- **Token:** mỗi thiết bị một token 256 bit. PC chỉ lưu SHA-256 của token, có thể thu hồi từng thiết bị.
- **Chuyển file chạy tiếp được:** upload dùng `Upload-Offset`, download dùng HTTP `Range`. Đang ghi thì để file tạm, xong mới đổi tên.
- **Không bóp tốc độ:** không giới hạn tốc độ ở server, buffer I/O 1 MiB, tối đa 3 file song song mỗi thiết bị.
- **Đo tốc độ:** endpoint không đụng ổ đĩa, để phân biệt giới hạn do WiFi với giới hạn do ổ đĩa.
- **Bảo mật thư mục:** chỉ truy cập được thư mục đã share, chặn `..`, symlink và junction.
- **Firewall:** mạng nhà đang ở profile Public, nên rule cho phép TCP 48920 đi từ `LocalSubnet` ở mọi profile, thay vì đổi profile mạng của máy.
- **mDNS trên Windows:** dùng API `DnsServiceRegister` có sẵn của hệ điều hành, gắn đúng IP của card có default gateway để không quảng bá nhầm IP của vEthernet/WSL.
- **iOS dùng URLSession foreground:** ưu tiên tốc độ tối đa và đường đi đơn giản, vì không có Mac để debug background session. Khi app xuống nền, chuyển file được giữ thêm khoảng 30 giây; quá thời gian đó thì tạm dừng và tự chạy tiếp khi mở lại. Trong lúc chuyển, màn hình không tự tắt.
- **Android dùng foreground service `dataSync`:** kèm `WifiLock` hiệu năng cao và `WakeLock`, chuyển tiếp được khi nằm nền.
- **Nơi lưu:** PC mặc định `Downloads\ShareFiles`. Android vào `Download/ShareFiles` (MediaStore). iOS vào `Documents/Received`, thấy trong app Files, có nút lưu vào Ảnh.
- **Thư mục share mặc định:** `Downloads` của PC, đổi được trong app.

## Giao diện

Chung một ngôn ngữ thiết kế "aurora":

- Nền tối xanh tím `#0B0A1F → #1A1040`, các vệt sáng tím `#7C4DFF`, hồng `#FF4D9D`, xanh `#22D3EE`.
- Thẻ kính: nền trắng 8%, viền trắng 14%, bo góc lớn.
- Nút chính gradient `#7C4DFF → #FF4D9D → #FFB547`.
- Tốc độ hiển thị số lớn, có thanh gradient chạy theo tiến độ.
- Thành công `#34D399`, lỗi `#FB7185`. Chữ trắng, phụ 70%.
- UI tiếng Việt.

Màn hình điện thoại (Android tự giãn hai cột trên tablet):

- **Ghép đôi:** quét QR, dán link.
- **Chuyển:** thẻ trạng thái PC, nút Gửi ảnh / Gửi file, danh sách đang chuyển và đã xong, tốc độ tức thời.
- **Lấy từ PC:** duyệt thư mục, chọn nhiều, tải về.
- **Cài đặt:** đo tốc độ, nhật ký, huỷ ghép đôi.

Cửa sổ PC: cột trái gồm trạng thái server, QR ghép đôi, danh sách thiết bị; cột phải gồm vùng kéo thả, danh sách chuyển, thư mục nhận và thư mục share.

## Lỗi và phục hồi

- Mất kết nối: tự thử lại với backoff, hỏi lại offset rồi chạy tiếp.
- Hết dung lượng PC: 507, báo rõ trên điện thoại, giữ file tạm.
- Token bị thu hồi: 401, app về màn ghép đôi.
- File nguồn trong hàng chờ đã bị xoá: 410, đánh dấu lỗi ở cả hai bên.
- Trùng tên ở PC: tự thêm hậu tố ` (1)`.
- Tên file từ client bị làm sạch, không nhận đường dẫn.

## Kiểm thử

- `pc/tests`: test tích hợp server thật qua TLS trên localhost, gồm ghép đôi, xác thực, chặn path traversal, upload/download có resume, hàng chờ, trùng tên.
- Android: unit test JVM, chạy end-to-end trên AVD với server thật qua `10.0.2.2`, chụp màn hình kiểm tra giao diện.
- iOS: không có Mac nên chỉ biên dịch được trên GitHub Actions, `swift test` cho phần logic. Giao diện và hành vi thật chỉ kiểm chứng được khi chủ repo cài lên máy.
- Đo tốc độ thật trên WiFi: chủ repo chạy màn đo tốc độ trong app.

## CI

- `.github/workflows/pc.yml`: test và publish bản PC.
- `.github/workflows/android.yml`: test và build APK.
- `.github/workflows/ios.yml`: XcodeGen, `swift test`, build IPA chưa ký.
- APK ký bằng khoá trong GitHub Secrets nếu có, nếu không thì bằng khoá debug tạo mới mỗi lần chạy (xem README). Khoá không bao giờ nằm trong repo.

## Trạng thái kiểm chứng (2026-11-05)

Đã kiểm chứng:

- Server: 53 test tích hợp qua TLS. Throughput qua TLS trên localhost cao hơn mức tối đa của dây 2.5G (1.2 GB/s từ RAM, 365 MB/s khi ghi đĩa).
- Android: 21 test JVM. Chạy trên AVD với server thật: ghép đôi qua deep link, duyệt, tải về, gửi lên, PC gửi sang qua hàng chờ, chạy tiếp sau khi tắt hẳn app PC giữa chừng, đo tốc độ. SHA-256 khớp ở mọi lần chuyển file.
- iOS: biên dịch được cho thiết bị thật trên GitHub Actions, 18 test logic.
- PC: quảng bá mDNS đọc được từ một client khác trên cùng máy, QR đọc được qua ảnh chụp màn hình, lần chạy đầu của bản Release.

Chưa kiểm chứng, vì không có thiết bị thật:

- App iOS chưa từng được chạy, kể cả giao diện.
- Chưa có số đo tốc độ thật qua WiFi. AVD đi qua NAT của emulator nên không đại diện.
- Điện thoại thật tìm PC qua mDNS, quét QR bằng camera thật.
- Android tiếp tục chuyển file khi nằm nền.
- Kéo thả file vào cửa sổ PC và menu khay hệ thống. Hàng chờ được thử bằng cách ghi trực tiếp `outbox.json`.
