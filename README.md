# Share Files

Chuyển file trực tiếp giữa điện thoại và PC trong mạng nhà, không qua cloud hay bên thứ 3. Gồm 3 app native: Windows, Android, iOS.

## Cài đặt

Lấy bản build mới nhất ở tab [Actions](../../actions), mở lần chạy xanh gần nhất rồi tải artifact.

| Máy | Workflow | Artifact | Cách cài |
|---|---|---|---|
| Windows | PC | `ShareFiles-PC` | Giải nén rồi chạy `ShareFiles.exe` |
| Android | Android | `ShareFiles-Android` | Cài `app-release.apk` |
| iPhone | iOS | `ShareFiles-iOS-unsigned` | Ký `ShareFiles-unsigned.ipa` bằng ESign rồi cài |

Lần đầu chạy, app PC xin quyền mở firewall cho cổng `48920`, chỉ trong mạng nội bộ.

## Ghép đôi

Mở app trên điện thoại rồi quét mã QR trên PC. Chỉ cần làm một lần, từ đó các máy tự tìm thấy nhau qua Bonjour/mDNS mà không cần gõ IP.

## Dùng

- **Gửi lên PC:** bấm **Gửi ảnh** hoặc **Gửi file**. File nằm ở `Downloads\ShareFiles`, đổi được trong app PC.
- **Lấy từ PC:** mở tab **Lấy từ PC**, chọn file rồi tải về. Chỉ thấy các thư mục đã thêm trong app PC, mặc định là `Downloads`.
- **PC gửi sang điện thoại:** kéo file vào cửa sổ app PC và chọn máy nhận. Máy đang mở app thì nhận ngay, chưa mở thì file chờ đến lần mở sau.
- **Đo tốc độ:** tab **Cài đặt** đo đường truyền thật mà không đụng tới ổ đĩa.

File nhận trên Android nằm ở `Download/ShareFiles`. Trên iPhone nằm trong app Tệp, mục **Trên iPhone → Share Files → Received**, và có nút lưu vào Ảnh.

## Giới hạn của iOS

iOS không cho app chạy nền lâu. Khi chuyển app khác hoặc khoá máy, việc chuyển file chỉ được giữ thêm khoảng 30 giây, sau đó tạm dừng và tự chạy tiếp khi mở lại app. Trong lúc đang chuyển, app giữ màn hình không tự tắt.

## Để đạt tốc độ tốt nhất

- Bật kênh **160 MHz** cho băng 5 GHz trong router.
- PC cắm dây vào cổng 2.5G của router.
- Nếu màn đo tốc độ cho số cao mà chuyển file thật lại chậm, nút cổ chai là ổ đĩa chứ không phải mạng.

## Phát triển

| Thư mục | Nội dung | Lệnh |
|---|---|---|
| `pc/` | Server và app Windows (.NET 10, WPF) | `dotnet test pc/tests/ShareFiles.Server.Tests` |
| `android/` | App Android (Kotlin, Compose) | `./gradlew :app:testDebugUnitTest :app:assembleRelease` |
| `ios/` | App iOS (Swift, SwiftUI) | `cd ios/Core && swift test`, build bằng XcodeGen trên macOS |
| `protocol/` | Hợp đồng giao thức chung | |

Gradle trên Windows không chạy được trong đường dẫn có ký tự ngoài ASCII. Nếu thư mục repo có dấu, hãy tạo một junction tên không dấu trỏ vào repo rồi build từ đó.

Android cần JDK 17 trở lên và SDK platform `android-37.0`.
