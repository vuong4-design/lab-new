# Huớng B — che vết ở tầng syscall + gỡ đặc trưng tĩnh

Ngày: 2026-07-28

Huớng A chỉ đổi cách *nạp* lib (memfd). Huớng B xử lý phần còn lại:
app tự đọc `/proc` và `/sys` của chính nó, đặc trưng chuỗi tĩnh trong `.so`,
và tên SONAME.

## 1. Native /proc + /sys read guard — `path_guard.cpp` (mới)

PLT-hook `open/openat/open64/fopen/fopen64/readlink/readlinkat` trên **mọi
module đang nạp** (duyệt `/proc/self/maps`, lấy từng `dev/inode` có map
executable). Với một allow-list nhỏ các đường nhạy cảm, trả về bản đã lọc
(memfd) thay vì file thật:
- `/proc/self/maps`, `/smaps`, `/proc/*/task/*/maps`: bỏ nguyên khối mapping
  nào chứa `/data/adb/`, `devicespooflab`, `liblsplant`, `libds_java_hooks`,
  `/memfd:`.
- `readlink`/`readlinkat` trên `/proc/self/fd/*`: nếu target trỏ tới các chuỗi
  nhạy cảm → ghi đè bằng đường dẫn vô hại.
- `/proc/meminfo`: ghi đè `MemTotal`/`MemAvailable` (đồng bộ với HardwareHooks)
  — chỉ khi profile có khóa `mem.total_kb`/`mem.available_kb`.
- `/sys/class/net/<if>/address`: trả MAC giả (đồng bộ với NetworkHooks +
  native getifaddrs) — chỉ khi profile có `wifi.mac`/`bluetooth.mac`.

An toàn:
- Mọi thao tác đọc nội bộ dùng **raw syscall** → không bao giờ đệ quy vào
  chính hook của mình.
- Mọi nhánh lỗi → gọi hàm gốc (I/O bình thường không bao giờ hỏng).
- Kill-switch: đặt khóa profile `native.guard=off` để tắt hoàn toàn.
- Chỉ cài cùng nhóm system-hook, chung một `ZygiskPltHookCommit()`.

## 2. Gỡ chuỗi tĩnh module path — `java_loader.cpp`

`/data/adb/modules/devicespooflab_hooks/system/lib*` trước nằm thẳng trong
`.rodata`. Nay mã hóa XOR (key 0x3C), giải mã lúc chạy (`DsDeobf`), nên
chuỗi đầy đủ không còn xuất hiện liền mạch trong binary → chống string scan.

## 3. Đa dạng hoá tên memfd — `java_loader.cpp`

Trước: 3 lib dùng chung `/memfd:jit-cache` (3 map trùng tên = bất thường).
Nay tên được chọn theo hash đường dẫn từ danh sách tên giống vùng ART/anon
hợp lệ.

## 4. Nguy trang SONAME — `CMakeLists.txt`

`libds_java_hooks.so` được đặt `-Wl,-soname,libperfetto_android.so`. Vì lib nạp
qua fd (memfd), tên file trên đĩa không ảnh hưởng việc nạp; `dl_iterate_phdr`
và soinfo->soname sẽ không còn báo “libds_java_hooks.so”.

## Hạn chế / còn lại
- **Chưa compile/test** (môi trường này không có NDK/gradle). Hook `open`
  toàn tiến trình là nhạy cảm — **bắt buộc chạy thử trên thiết bị** trước khi
  phát hành (rủi ro boot-loop nếu lỗi). Có thể tắt nhanh bằng `native.guard=off`.
- `liblsplant.so` là prebuilt (find_package) nên SONAME của nó chưa đổi được
  từ đây; guard kiểu (1) vẫn lọc tên này khỏi maps.
- Tên gói `com.devicespooflab.hooks`, id module `devicespooflab_hooks`, và
  symbol JNI `Java_com_devicespooflab_hooks_...` vẫn plaintext — muốn sạch hẳn
  phải đổi tên gói + đăng ký native bằng `RegisterNatives` tên trung tính
  (refactor lớn, nên làm riêng).
- App đọc `/proc` bằng raw syscall (không qua libc) sẽ bỏ qua guard này —
  muốn chặn phải hook tầng syscall (seccomp/svc), ngoài phạm vi hiện tại.
