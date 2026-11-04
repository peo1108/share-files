# Share Files Implementation Plan

> **For agentic workers:** Chủ repo đã uỷ quyền tự chốt, thực thi inline theo superpowers:executing-plans. Steps dùng checkbox (`- [ ]`).

**Goal:** Ba app native (Windows, Android, iOS) chuyển file trực tiếp qua LAN nhà.

**Architecture:** PC chạy HTTPS server, điện thoại là client. Giao thức nằm ở `protocol/PROTOCOL.md`; logic server tách khỏi UI.

**Tech Stack:** .NET 10, ASP.NET Core Kestrel, WPF, xUnit; Kotlin, Compose, OkHttp, CameraX, ZXing, AGP 9.4.1, Gradle 9.7.1; Swift, SwiftUI, URLSession, Network.framework, XcodeGen.

**Spec:** `docs/superpowers/specs/2026-11-04-share-files-design.md`

## Global Constraints

- Cổng `48920`, dịch vụ `_sharefiles._tcp`, API `/v1`, HTTP/1.1 qua TLS.
- Không dịch vụ bên ngoài ở runtime, không giới hạn tốc độ, buffer I/O 1 MiB, tối đa 3 file song song mỗi thiết bị.
- Secret ghép đôi dùng một lần, hết hạn sau 5 phút. PC chỉ lưu SHA-256 của token.
- UI tiếng Việt, theme aurora theo spec.
- Không đưa khoá hay dữ liệu riêng vào repo (repo public).

## Review Focus

1. Tên file từ client chứa `..`, `/`, `\`, tên thiết bị Windows (`CON`) hoặc ký tự cấm: phải làm sạch, không bao giờ ghi ra ngoài thư mục nhận.
2. Đường dẫn browse/download thoát khỏi root bằng `..`, đường dẫn tuyệt đối, junction: phải 403.
3. Upload đứt giữa chừng rồi gửi lại với offset cũ: giữ phần đã ghi, offset lệch trả 409 kèm offset đúng.
4. Trùng tên ở thư mục nhận: thêm ` (1)`, không ghi đè file sẵn có.
5. Secret đã dùng hoặc hết hạn, token đã thu hồi: bị từ chối ngay.

---

### Task 1: Server core (`pc/src/ShareFiles.Server`)

**Files:** `ServerOptions.cs`, `ConfigStore.cs`, `Certificates.cs`, `DeviceRegistry.cs`, `PairingService.cs`, `PathGuard.cs`, `FileNames.cs`, `UploadManager.cs`, `OutboxStore.cs`, `ShareServer.cs`, `Endpoints.cs`. Test: `pc/tests/ShareFiles.Server.Tests`.

**Produces:** `ShareServer.StartAsync/StopAsync`, `PairingService.CreateTicket()`, `OutboxStore.Enqueue(deviceId, path)`, sự kiện `ShareServer.TransferChanged`.

- [ ] Viết test tích hợp qua TLS trên cổng ngẫu nhiên: `Info_NoAuth`, `Pair_SecretOneUse`, `Pair_Expired`, `Auth_Missing401`, `Auth_RevokedToken401`, `Browse_ListsDirsFirst`, `Browse_TraversalForbidden`, `Download_Range`, `Upload_Complete`, `Upload_ResumeAfterAbort`, `Upload_OffsetMismatch409`, `Upload_DuplicateNameGetsSuffix`, `Upload_SanitizesName`, `Upload_EmptyFile`, `Outbox_ListDownloadAck`, `Outbox_SourceGone410`, `Outbox_OnlyOwnDevice`, `Speedtest_ExactBytes`.
- [ ] Chạy `dotnet test`, xác nhận fail vì chưa có code.
- [ ] Viết code server.
- [ ] Chạy `dotnet test`, xác nhận pass.
- [ ] Đo throughput localhost qua TLS để chắc server không phải nút cổ chai.
- [ ] Commit.

### Task 2: App Windows (`pc/src/ShareFiles.App`)

**Files:** `App.xaml`, `MainWindow.xaml`, `Theme.xaml`, `MdnsAdvertiser.cs`, `FirewallSetup.cs`, `TrayIcon.cs`, `AutoStart.cs`.

**Consumes:** API của Task 1.

- [ ] Viết `MdnsAdvertiser` bằng `DnsServiceRegister`, kiểm chứng bằng Python `zeroconf`.
- [ ] Viết WPF UI: QR, thiết bị, kéo thả, danh sách chuyển, thư mục.
- [ ] Chạy thật, chụp màn hình kiểm tra.
- [ ] Commit.

### Task 3: App Android (`android/`)

**Files:** `core/` (Api, Transfer engine, Pinning, Discovery), `service/TransferService.kt`, `ui/` (theme + màn hình), test JVM.

**Consumes:** Hợp đồng ở `protocol/PROTOCOL.md`.

- [ ] Viết test JVM với MockWebServer: `PairLink_Parse`, `Upload_ResumesFromServerOffset`, `Download_ResumesWithRange`, `Pinning_RejectsWrongFingerprint`.
- [ ] Chạy test, xác nhận fail.
- [ ] Viết core, chạy test pass.
- [ ] Viết service và UI Compose.
- [ ] Chạy trên AVD `CamY700Gen5` với server thật, thử ghép đôi qua deep link, gửi, duyệt, tải, chụp màn hình.
- [ ] Commit.

### Task 4: App iOS (`ios/`)

**Files:** `project.yml`, `Package.swift`, `Sources/ShareFilesCore` (logic), `ShareFiles/` (SwiftUI), `Tests/`.

- [ ] Viết test `swift test`: `PairLinkParsing`, `SpeedMeter`, `TransferPlan`.
- [ ] Viết core và UI theo cùng hợp đồng và màn hình như Android.
- [ ] Viết `.github/workflows/ios.yml`, build trên GitHub Actions tới khi xanh.
- [ ] Commit.

### Task 5: Hoàn thiện

- [ ] `android.yml`, sửa `pc.yml` nếu cần, README hướng dẫn cài và ghép đôi.
- [ ] Toàn bộ CI xanh, có artifact tải được.
- [ ] Chạy bước review toàn repo và sửa lỗi tìm được.
- [ ] Commit, push.
