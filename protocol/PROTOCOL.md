# Share Files protocol v1

Hợp đồng chung giữa server trên PC và các client Android, iOS.

## Kết nối

- Khám phá: Bonjour/mDNS, kiểu dịch vụ `_sharefiles._tcp`, TXT gồm `id` (server id), `v` (phiên bản API, hiện là `1`).
- Cổng mặc định: `48920`.
- Giao thức: HTTPS, HTTP/1.1, TLS 1.2 trở lên.
- Chứng chỉ: tự ký. Client bỏ qua CA và hostname, chỉ chấp nhận khi SHA-256 của chứng chỉ (DER) bằng fingerprint đã lưu, dạng hex thường.
- Mọi JSON dùng camelCase, UTF-8. Thời gian là ISO 8601 UTC.

## Ghép đôi

QR hoặc deep link:

```
sharefiles://pair?id=<serverId>&name=<tên PC>&fp=<fingerprint>&secret=<base64url>&port=48920&addrs=192.168.50.211,10.0.2.2
```

`secret` dài 128 bit, dùng một lần, hết hạn sau 5 phút. Client kết nối tới Bonjour hoặc từng địa chỉ trong `addrs`, pin theo `fp`, rồi gọi `POST /v1/pair`.

## Xác thực

Mọi endpoint trừ `GET /v1/info` và `POST /v1/pair` cần header:

```
Authorization: Bearer <token>
```

Thiếu hoặc sai trả về `401`.

## Lỗi

```json
{ "code": "not_found", "message": "..." }
```

| HTTP | code | Nghĩa |
|---|---|---|
| 400 | `bad_request` | Thiếu hoặc sai tham số |
| 401 | `unauthorized` | Token sai hoặc đã thu hồi |
| 403 | `pair_invalid`, `path_forbidden` | Secret sai/hết hạn; đường dẫn ngoài thư mục share |
| 404 | `not_found` | Không có |
| 409 | `offset_mismatch` | `Upload-Offset` lệch, thân lỗi có thêm `offset` |
| 410 | `source_gone` | File nguồn của hàng chờ đã bị xoá |
| 507 | `insufficient_storage` | PC hết dung lượng |

## Endpoint

### `GET /v1/info`

```json
{ "id": "…", "name": "PC-CAM", "apiVersion": 1 }
```

### `POST /v1/pair`

```json
{ "secret": "…", "deviceId": "uuid", "deviceName": "Y700", "platform": "android" }
```

`platform` là `android` hoặc `ios`. Trả về:

```json
{ "token": "…", "serverId": "…", "serverName": "PC-CAM" }
```

Ghép lại cùng `deviceId` thì token cũ mất hiệu lực.

### `GET /v1/roots`

```json
[ { "id": "…", "name": "Downloads" } ]
```

### `GET /v1/browse?root=<id>&path=<đường dẫn tương đối>`

`path` rỗng là gốc, phân cách bằng `/`.

```json
{
  "root": "…",
  "path": "phim",
  "entries": [
    { "name": "a.mp4", "isDirectory": false, "size": 123, "modifiedAt": "2026-11-04T10:00:00Z" }
  ]
}
```

Thư mục đứng trước, rồi theo tên. Symlink và junction bị bỏ qua.

### `GET /v1/download?root=<id>&path=<đường dẫn>`

Trả nội dung file, hỗ trợ `Range`, có `ETag`.

### `POST /v1/uploads`

```json
{ "fileKey": "uuid do client tạo", "name": "IMG_1.HEIC", "size": 123, "modifiedAt": "2026-11-04T10:00:00Z" }
```

`modifiedAt` có thể bỏ. Cùng thiết bị và cùng `fileKey` thì trả lại lượt upload cũ để chạy tiếp. Trả về:

```json
{ "id": "…", "offset": 0, "completed": false, "savedName": null }
```

File rỗng hoàn tất ngay với `completed: true`.

### `PATCH /v1/uploads/{id}`

Header `Upload-Offset: <n>`, thân là byte thô. Mất kết nối giữa chừng vẫn giữ phần đã ghi. Trả về:

```json
{ "id": "…", "offset": 123, "completed": true, "savedName": "IMG_1.HEIC" }
```

Khi `offset` bằng `size`, server đổi tên file tạm thành file chính, trùng tên thì thêm ` (1)`.

### `DELETE /v1/uploads/{id}`

Huỷ và xoá phần đã nhận. Trả về `204`.

### `GET /v1/outbox`

Hàng chờ PC gửi cho thiết bị đang đăng nhập.

```json
[ { "id": "…", "name": "a.zip", "size": 123, "modifiedAt": "2026-11-04T10:00:00Z", "sourceMissing": false } ]
```

### `GET /v1/outbox/{id}/content`

Nội dung file, hỗ trợ `Range`. File nguồn đã mất thì `410`.

### `POST /v1/outbox/{id}/ack`

Client báo đã nhận đủ, mục bị xoá khỏi hàng chờ. Trả về `204`.

### `DELETE /v1/outbox/{id}`

Client từ chối mục đó. Trả về `204`.

### `GET /v1/speedtest/download?bytes=<n>`

Trả `n` byte ngẫu nhiên từ RAM, không đụng ổ đĩa. Tối đa 4 GiB.

### `POST /v1/speedtest/upload`

Server đọc rồi bỏ thân request.

```json
{ "bytes": 123, "seconds": 0.5 }
```
