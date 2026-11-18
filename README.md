# car-helper

App Android nhận diện và **đọc to biển báo giao thông Việt Nam**, xử lý hoàn toàn trên máy (không cần mạng).

## Cách hoạt động

```
CameraX (1280x720, RGBA, chỉ trong RAM)
  → giới hạn 5 FPS theo timestamp (tự hạ xuống 3/2/1 FPS khi máy nóng)
  → xoay thẳng + cắt vùng phía trên bên phải (ROI) + letterbox vào input model
  → YOLO TFLite (GPU nếu có, không thì CPU 4 luồng)
  → NMS không phân biệt lớp
  → bỏ phiếu 3/5 frame + không đọc lại cùng biển trong 30 giây
  → TextToSpeech tiếng Việt (tạm hạ nhỏ nhạc khi đọc)
```

Cấu hình camera: FPS cảm biến 10–15, tắt auto-focus và khoá nét ở ~6,7 m (siêu tiêu cự),
**giữ auto-exposure** (ép phơi sáng tay sẽ làm ảnh đen khi trời tối).
Nút **Màn hình đen** tắt preview và hạ độ sáng về 0 để máy mát hơn; chạm vào màn hình để quay lại.

## Cấu trúc

| Đường dẫn | Nội dung |
|---|---|
| `app/.../MainActivity.kt` | Ghép các phần, quyền camera, chế độ màn hình đen |
| `app/.../camera/CameraController.kt` | CameraX + Camera2Interop (FPS, lấy nét) |
| `app/.../detection/SignAnalyzer.kt` | Giới hạn tần suất, cắt ROI, map toạ độ |
| `app/.../detection/SignDetector.kt` | Chạy model TFLite, giải mã output YOLO |
| `app/.../detection/VoteTracker.kt` | Lọc nhiễu đa khung hình, chống đọc lặp |
| `app/.../speech/SignSpeaker.kt` | TTS tiếng Việt + audio focus |
| `app/.../system/ThermalGovernor.kt` | Giảm FPS theo nhiệt độ máy |
| `app/src/main/assets/labels.txt` | Danh sách lớp, **thứ tự = classId** |
| `training/train.py` | Huấn luyện YOLO và export TFLite vào assets |

## Bắt đầu

1. **Có model.** App chạy được khi chưa có model (chỉ hiện camera và báo "Model: chưa có").
   Để nhận diện được, cần file `app/src/main/assets/traffic_signs.tflite`:
   ```bash
   pip install -r training/requirements.txt
   python training/train.py --dataset /duong/dan/dataset
   ```
   Dataset theo định dạng YOLO, classId đúng thứ tự `labels.txt`. Nguồn gợi ý: dataset biển báo VN trên
   Kaggle/Roboflow (ví dụ từ Zalo AI 2020), cộng thêm video dashcam bạn tự quay rồi gán nhãn bằng Roboflow/CVAT.
   Không bật lật ngang ảnh khi augment (script đã đặt `fliplr=0`).
2. **Build.** Mở thư mục bằng Android Studio (JDK 17, SDK 35), hoặc:
   ```bash
   gradle wrapper --gradle-version 8.11.1   # lần đầu, nếu chưa có gradlew
   ./gradlew :app:assembleDebug :app:testDebugUnitTest
   ```
3. **Cài lên máy**: `./gradlew :app:installDebug`. Máy cần có gói giọng đọc tiếng Việt
   (Cài đặt → Chuyển văn bản thành giọng nói → Google → tải tiếng Việt).

## Lưu ý an toàn

App chỉ là công cụ **hỗ trợ**. Người lái vẫn phải tự quan sát biển báo; không thao tác với điện thoại khi đang lái.
