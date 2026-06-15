# GachNo - Notification Forwarder Lite

Ứng dụng Android tinh gọn chuyển tiếp thông báo (App Notification) qua Webhook (HTTP POST).

**Mục đích**: Khi tài khoản ngân hàng nhận tiền → App ngân hàng hiện thông báo → GachNo bắt thông báo → POST dữ liệu lên web trung tâm để tự động gạch nợ học phí.

## ✨ Tính năng

- 📱 Lắng nghe thông báo từ các app đã chọn
- 🌐 Chuyển tiếp qua Webhook (HTTP POST JSON)
- 🔍 Lọc app theo nhu cầu (chọn app ngân hàng cụ thể)
- 📋 Nhật ký chuyển tiếp với trạng thái (thành công/thất bại)
- 🌍 Song ngữ Tiếng Việt / English
- 🔄 Tự động chạy sau khi khởi động lại điện thoại
- 🧪 Nút test webhook

## 📦 Cài đặt

1. Vào [Releases](../../releases) → tải file `app-debug.apk`
2. Cài đặt APK trên điện thoại Android (cần bật "Cài từ nguồn không xác định")
3. Mở app GachNo

## 🚀 Hướng dẫn sử dụng

### Bước 1: Cấp quyền
- Mở app → Bấm **"Cấp quyền đọc thông báo"**
- Trong cài đặt hệ thống, bật **GachNo**

### Bước 2: Nhập Webhook URL
- Nhập địa chỉ URL webhook của web trung tâm
- Bấm **"🚀 Thử Webhook"** để kiểm tra kết nối

### Bước 3: Chọn ứng dụng
- Bấm **"Chọn"** ở mục Lọc ứng dụng
- Tích chọn các app ngân hàng (MB Bank, Vietcombank, v.v.)

### Bước 4: Bật chuyển tiếp
- Bật công tắc **"Chuyển tiếp thông báo"**
- Xong! Khi có thông báo mới từ app đã chọn, GachNo sẽ tự động gửi lên server

## 📤 Webhook Format

GachNo gửi HTTP POST với body JSON:

```json
{
  "app_name": "MB Bank",
  "package_name": "com.mbmobile",
  "title": "Thông báo giao dịch",
  "content": "TK 0123456789 +500,000 VND lúc 15:30 15/06/2026. SD: 1,200,000 VND",
  "timestamp": "2026-06-15T15:30:00+07:00",
  "device_name": "Samsung Galaxy A54"
}
```

## 🔧 Build từ source

Yêu cầu: JDK 17+

```bash
# Clone repo
git clone <repo-url>
cd thuhocphitudong

# Build debug APK
./gradlew assembleDebug

# APK ở: app/build/outputs/apk/debug/app-debug.apk
```

## 🤖 Auto Build (GitHub Actions)

- Push code lên `main` → GitHub tự động build APK
- Tạo tag `v1.0.0` → GitHub tự động tạo Release với APK đính kèm

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 📋 So với SmsForwarder gốc

| | SmsForwarder | GachNo |
|---|---|---|
| Chuyển tiếp SMS | ✅ | ❌ |
| Chuyển tiếp cuộc gọi | ✅ | ❌ |
| Chuyển tiếp App Notification | ✅ | ✅ |
| Webhook | ✅ | ✅ |
| DingTalk/WeChat/Feishu/Telegram | ✅ | ❌ |
| Ngôn ngữ | 中文/EN | **VI/EN** |
| Giao diện | Phức tạp | **1 màn hình** |
