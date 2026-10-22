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
- Kill-switch: `native.guard=off` tắt full guard; `hooks.uptime_spoof=0` tắt
  uptime guard.
- Full guard cài cùng nhóm system-hook; uptime guard vẫn cài được ở chế độ
  core-only.

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


---

## HOTFIX (2026-07-28, sau build đầu tiên)

**Triệu chứng:** boot-loop. `system_server` bị `SIGSEGV (SEGV_ACCERR)` lặp
lại, lỗi ở cùng offset trang (`...f40`) mỗi lần → cùng một lệnh lỗi dưới ASLR.

**Nguyên nhân:** `InstallPathGuardHooks()` PLT-hook họ `open/openat/fopen`
trên *mọi module* ngay trong `system_server`. Đây vừa thừa (trình dò đọc
`/proc` của *chính app*, không phải system_server) vừa cực kỳ nguy hiểm:
`open/openat` được gọi trong những ngữ cảnh rất sớm/nhạy cảm của
system_server, chỉ cần một lỗi trên đường đó là sập cả máy. Đúng rủi ro
đã cảnh báo.

**Khắc phục:**
1. Path guard chuyển thành **opt-in**: chỉ chạy khi profile đặt
   `native.guard=on`. Mặc định **TẮT** → bản cài gốc không bao giờ boot-loop
   vì module này nữa.
2. **Từ chối tuyệt đối trong system_server**: `IsSystemServer()` đọc
   `/proc/self/cmdline` bằng raw syscall; nếu là system_server thì không cài
   guard dù có bật flag.
3. Guard (khi bật) chỉ cài trong **app process** — đúng nơi trình dò đọc
   `/proc/self/maps` của chính nó.

Ba thay đổi tĩnh còn lại của Hướng B (XOR path, đa dạng tên memfd, soname)
không đụng tới `open()` runtime nên giữ nguyên, an toàn.

**Cách bật guard sau khi máy đã ổn định (tự chịu trách nhiệm, phải test):**
thêm dòng `native.guard=on` vào `device_profile.conf`. Nếu app nào đó
không ổn → xóa dòng đó, guard tắt lại. Guard **không bao giờ** chạm
system_server.


---

## HOTFIX 2 (2026-07-28, sau khi đã boot ổn) — 2 phát hiện còn lại

Máy đã boot & spoof hoạt động. Detector còn báo:
1. **Detected Magisk Module: liblsplant.so** (tên soname).
2. **Found Injection / Anonymous executable code**: `/memfd:jit-cache (deleted)`
   r-xp và các map `[anonymous]` r-xp.

### Bug thật được sửa (java_loader.cpp)
Tên memfd hiện trong `/proc/self/maps` do `memfd_create(name)` quyết định, chứ
KHÔNG phải `identity` truyền cho `android_dlopen_ext`. Bản B2 đa dạng hóa
nhầm `identity` nên 6 lib vẫn hiện `/memfd:jit-cache`. Nay tính `pick` (tên
kiểu ART theo hash đường dẫn) TRƯỚC rồi truyền thẳng cho `memfd_create(pick)`,
đồng bộ với `identity`. => nhãn maps không còn trùng lặp.

### Guard được mở rộng (path_guard.cpp, chỉ khi native.guard=on, app-only)
- **FilterMaps + IsInjectionShape**: ngoài lọc theo tên, giờ bỏ luôn mọi map
  **executable không backed bởi file thật**: `(deleted)`, `/memfd:`, `[anon...]`,
  hoặc anonymous (inode 0, không có path). => ẩn được “Found Injection” và
  “Anonymous executable code” khỏi maps mà app tự đọc.
- **Hook dl_iterate_phdr**: bọc callback, bỏ qua entry có `dlpi_name` nhạy cảm
  (ví dụ `liblsplant.so`). Nhiều detector đọc soname qua API này thay vì /proc.

### GIỚI HẠN QUAN TRỌNG (phải hiểu rõ)
Hai phát hiện này là **shape-based** (hình dạng code tiêm), khác với các vết
“đường dẫn” trước. Về nguyên tắc, một tiến trình đọc `/proc/self/maps` của
CHÍNH NÓ sẽ luôn thấy code tiêm dưới dạng map executable không phải file
hệ thống. Cách DUY NHẤT ẩn là **can thiệp chính việc app đọc maps** = path
guard. Nhưng:
- Guard chỉ có tác dụng với detector đọc qua **libc open/fopen/dl_iterate_phdr**.
- Detector đọc bằng **raw syscall** (openat trực tiếp), hoặc đọc
  `/proc/self/map_files/`, `/proc/self/smaps_rollup`, hoặc parse ELF trong bộ
  nhớ → **vượt qua** guard này. Muốn chặn tận gốc phải làm ở tầng
  syscall/seccomp hoặc kernel — ngoài phạm vi userspace hook.
- Trampoline inline-hook (Dobby/lsplant) vốn là trang exec ẩn danh; không thể
  bỏ hoàn toàn mà vẫn hook — chỉ có thể ẩn khỏi *góc nhìn maps*.

Để thử nghiệm chống 2 phát hiện này: đặt `native.guard=on` trong
`device_profile.conf` (chỉ tác dộng app, không động system_server), rồi test kỹ.

---

## v1.41 — Uptime guard và service/timestamp spoofing

Trạng thái: đã triển khai trong working tree; Java compile pass, native CI và
device test chưa chạy.

### Uptime path guard (uptime-only)

Ngoài full guard `native.guard=on`, v1.41 thêm chế độ hẹp chỉ phục vụ uptime:

- Chỉ can thiệp `/proc/uptime` và dòng `btime` trong `/proc/stat`.
- Không lọc maps, smaps, meminfo, mounts, CPU topology, hay `/sys`.
- Chỉ cài trong app process; `IsSystemServer()` vẫn từ chối tuyệt đối.
- Có thể chạy khi `native.guard=off` nếu `hooks.uptime_spoof=1` và app trong
  scope. Full guard và uptime guard dùng chung callback nhưng độc lập flag.
- Kill-switch riêng: `hooks.uptime_spoof=0` (không cần tắt full guard).

### Native time surface

- Companion chọn target một lần mỗi lifetime: mặc định random 1–7 ngày.
- Offset = target - real uptime, inject vào profile.
- `clock_gettime` cộng offset cho `CLOCK_MONOTONIC`, `MONOTONIC_RAW`,
  `BOOTTIME`, `BOOTTIME_ALARM`; `sysinfo.uptime` cũng được cộng.
- Không hook Java `SystemClock`: AOSP đi qua native `clock_gettime`, hook thêm
  sẽ cộng offset hai lần.
- `system_server`, module UI, app ngoài scope giữ uptime thật.

### Service list và timestamp

- `ServiceManager.listServices`/`getService`/`checkService` lọc theo keyword
  case-insensitive; đây là mô hình keyword, không phải baseline tuyệt đối.
  Direct binder `IServiceManager` vẫn bypass.
- Timestamp chỉ patch self package: neo `ro.build.date.utc` + 60–120 ngày;
  `lastUpdateTime` = anchor + 0–14 ngày và clamp `<= now`; phủ proxy/list.
- `hooks.service_list_spoof=1`, `hooks.package_info_times=1` là default.

### Còn lại

- Chưa test device/emulator; chưa có CI run cho changes này.
- `/proc/self/stat` starttime và raw syscall/vDSO clock reads vẫn bypass.
- `/proc/uptime` idle được clamp theo uptime đã chỉnh; chỉ là heuristic.
- Đổi min/max ngày cần companion restart/relaunch để chọn target mới.
