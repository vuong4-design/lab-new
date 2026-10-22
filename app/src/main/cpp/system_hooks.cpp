#include "ds_state.h"

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <ifaddrs.h>
#include <linux/if_packet.h>
#include <linux/sockios.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sched.h>
#include <string>
#include <time.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <sys/vfs.h>
#include <unistd.h>

namespace ds {

std::atomic<bool> g_uptime_active{false};
std::atomic<long long> g_uptime_offset_seconds{0};

namespace {

int (*orig_clock_gettime)(clockid_t, struct timespec*) = nullptr;
int (*orig_uname)(struct utsname*) = nullptr;
int (*orig_gethostname)(char*, size_t) = nullptr;
int (*orig_getifaddrs)(struct ifaddrs**) = nullptr;
long (*orig_sysconf)(int) = nullptr;
int (*orig_sysinfo)(struct sysinfo*) = nullptr;
int (*orig_sched_getaffinity)(pid_t, size_t, cpu_set_t*) = nullptr;
int (*orig_ioctl)(int, unsigned long, void*) = nullptr;
int (*orig_statfs)(const char*, struct statfs*) = nullptr;
int (*orig_statvfs)(const char*, struct statvfs*) = nullptr;

std::string LookupOr(const char* key, const char* fallback) {
    std::string out;
    if (LookupProperty(key, out) && !out.empty()) return out;
    return fallback ? fallback : "";
}

long LookupLong(const char* key, long fallback) {
    std::string out;
    if (!LookupProperty(key, out) || out.empty()) return fallback;
    char* end = nullptr;
    long value = strtol(out.c_str(), &end, 10);
    if (end == out.c_str() || *end != '\0') return fallback;
    return value;
}

int CpuCoreCount() {
    long configured = LookupLong("hardware.cpu.cores",
                                 LookupLong("cpuinfo.cores", 8));
    if (configured < 1) configured = 1;
    if (configured > CPU_SETSIZE) configured = CPU_SETSIZE;
    return static_cast<int>(configured);
}

bool IsAdjustableMonotonicClock(clockid_t clockId) {
    if (clockId == CLOCK_MONOTONIC || clockId == CLOCK_MONOTONIC_RAW) {
        return true;
    }
#ifdef CLOCK_BOOTTIME
    if (clockId == CLOCK_BOOTTIME) return true;
#endif
#ifdef CLOCK_BOOTTIME_ALARM
    if (clockId == CLOCK_BOOTTIME_ALARM) return true;
#endif
    return false;
}

int my_clock_gettime(clockid_t clockId, struct timespec* tp) {
    int rc = 0;
    if (orig_clock_gettime != nullptr) {
        rc = orig_clock_gettime(clockId, tp);
    } else {
#ifdef __NR_clock_gettime
        rc = static_cast<int>(syscall(__NR_clock_gettime, clockId, tp));
#else
        errno = ENOSYS;
        rc = -1;
#endif
    }
    if (rc != 0 || tp == nullptr || !IsUptimeSpoofEnabled()) {
        return rc;
    }
    if (!IsAdjustableMonotonicClock(clockId)) return rc;

    long long adjusted = static_cast<long long>(tp->tv_sec)
            + g_uptime_offset_seconds.load(std::memory_order_relaxed);
    if (adjusted < 0) adjusted = 0;
    tp->tv_sec = static_cast<time_t>(adjusted);
    return rc;
}

int my_uname(struct utsname* u) {
    int rc = orig_uname ? orig_uname(u) : -1;
    if (u != nullptr) {
        std::string release = LookupOr("kernel.osrelease", "5.10.157-android13");
        std::string version = LookupOr("kernel.version",
                                       "#1 SMP PREEMPT Tue Dec  3 21:01:46 UTC 2024");
        std::string nodename = LookupOr("kernel.hostname", "localhost");

        // sysname/machine left as Linux/aarch64.
        snprintf(u->release, sizeof(u->release), "%s", release.c_str());
        snprintf(u->version, sizeof(u->version), "%s", version.c_str());
        snprintf(u->nodename, sizeof(u->nodename), "%s", nodename.c_str());
    }
    return rc;
}

int my_gethostname(char* name, size_t len) {
    if (name == nullptr || len == 0) {
        return orig_gethostname ? orig_gethostname(name, len) : -1;
    }
    std::string h = LookupOr("kernel.hostname", "localhost");
    size_t n = h.size();
    if (n + 1 > len) n = len - 1;
    memcpy(name, h.data(), n);
    name[n] = '\0';
    return 0;
}

bool ParseMac(const std::string& s, uint8_t out[6]) {
    if (s.size() < 17) return false;
    auto hex = [](char c, int& v) {
        if (c >= '0' && c <= '9') { v = c - '0'; return true; }
        if (c >= 'a' && c <= 'f') { v = c - 'a' + 10; return true; }
        if (c >= 'A' && c <= 'F') { v = c - 'A' + 10; return true; }
        return false;
    };
    for (int i = 0; i < 6; i++) {
        int hi = 0, lo = 0;
        if (!hex(s[i*3 + 0], hi)) return false;
        if (!hex(s[i*3 + 1], lo)) return false;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return true;
}

int my_getifaddrs(struct ifaddrs** ifap) {
    int rc = orig_getifaddrs ? orig_getifaddrs(ifap) : -1;
    if (rc != 0 || ifap == nullptr || *ifap == nullptr) return rc;

    std::string wifiMac = LookupOr("wifi.mac", "");
    std::string btMac   = LookupOr("bluetooth.mac", "");
    uint8_t wifiBytes[6] = {0}, btBytes[6] = {0};
    bool haveWifi = !wifiMac.empty() && ParseMac(wifiMac, wifiBytes);
    bool haveBt   = !btMac.empty()   && ParseMac(btMac,   btBytes);

    for (struct ifaddrs* it = *ifap; it != nullptr; it = it->ifa_next) {
        if (it->ifa_name == nullptr || it->ifa_addr == nullptr) continue;
        if (it->ifa_addr->sa_family != AF_PACKET) continue;
        auto* sll = reinterpret_cast<struct sockaddr_ll*>(it->ifa_addr);
        if (sll->sll_halen != 6) continue;

        const char* n = it->ifa_name;
        if (haveWifi && (strncmp(n, "wlan", 4) == 0)) {
            memcpy(sll->sll_addr, wifiBytes, 6);
        } else if (haveBt && (strncmp(n, "bt", 2) == 0 || strncmp(n, "bnep", 4) == 0)) {
            memcpy(sll->sll_addr, btBytes, 6);
        } else if (strncmp(n, "lo", 2) == 0) {
            // loopback
        } else {
            memset(sll->sll_addr, 0, 6);
        }
    }
    return rc;
}

long my_sysconf(int name) {
    if (name == _SC_NPROCESSORS_CONF || name == _SC_NPROCESSORS_ONLN) {
        return CpuCoreCount();
    }
    return orig_sysconf ? orig_sysconf(name) : -1;
}

int my_sysinfo(struct sysinfo* info) {
    int rc = orig_sysinfo ? orig_sysinfo(info) : -1;
    if (rc != 0 || info == nullptr) return rc;

    if (IsUptimeSpoofEnabled()) {
        long long adjusted = static_cast<long long>(info->uptime)
                + g_uptime_offset_seconds.load(std::memory_order_relaxed);
        if (adjusted < 0) adjusted = 0;
        info->uptime = static_cast<unsigned long>(adjusted);
    }

    long totalKb = LookupLong("memory.total_kb", -1);
    long availableKb = LookupLong("memory.available_kb", -1);
    unsigned long long unit = info->mem_unit == 0 ? 1 : info->mem_unit;
    if (totalKb > 0) {
        info->totalram = static_cast<unsigned long>(
                static_cast<unsigned long long>(totalKb) * 1024ULL / unit);
    }
    if (availableKb >= 0 && totalKb > 0) {
        if (availableKb > totalKb) availableKb = totalKb;
        info->freeram = static_cast<unsigned long>(
                static_cast<unsigned long long>(availableKb) * 1024ULL / unit);
    }
    return rc;
}

int my_sched_getaffinity(pid_t pid, size_t cpusetsize, cpu_set_t* mask) {
    int rc = orig_sched_getaffinity
            ? orig_sched_getaffinity(pid, cpusetsize, mask)
            : -1;
    if (rc != 0 || mask == nullptr) return rc;

    int cores = CpuCoreCount();
    if (cores > static_cast<int>(cpusetsize * 8)) {
        cores = static_cast<int>(cpusetsize * 8);
    }
    CPU_ZERO(mask);
    for (int i = 0; i < cores; ++i) {
        CPU_SET(i, mask);
    }
    return 0;
}

bool IsDataPath(const char* path) {
    if (path == nullptr) return false;
    return strcmp(path, "/data") == 0
            || strncmp(path, "/data/", 6) == 0
            || strncmp(path, "/storage/emulated", 17) == 0;
}

int my_ioctl(int fd, unsigned long request, void* arg) {
    int rc = orig_ioctl ? orig_ioctl(fd, request, arg) : -1;
    if (rc != 0 || request != SIOCGIFHWADDR || arg == nullptr) {
        return rc;
    }

    auto* ifr = static_cast<struct ifreq*>(arg);
    std::string mac;
    uint8_t bytes[6] = {0};
    if (strncmp(ifr->ifr_name, "wlan", 4) == 0) {
        if (LookupProperty("wifi.mac", mac) && ParseMac(mac, bytes)) {
            memcpy(ifr->ifr_hwaddr.sa_data, bytes, sizeof(bytes));
        }
    } else if (strncmp(ifr->ifr_name, "bt", 2) == 0
            || strncmp(ifr->ifr_name, "bnep", 4) == 0) {
        if (LookupProperty("bluetooth.mac", mac) && ParseMac(mac, bytes)) {
            memcpy(ifr->ifr_hwaddr.sa_data, bytes, sizeof(bytes));
        }
    }
    return rc;
}

int my_statfs(const char* path, struct statfs* buf) {
    int rc = orig_statfs ? orig_statfs(path, buf) : -1;
    if (rc != 0 || buf == nullptr || !IsDataPath(path)) return rc;

    long total = LookupLong("storage.total_bytes", -1);
    long available = LookupLong("storage.available_bytes", -1);
    long blockSize = buf->f_bsize > 0 ? buf->f_bsize : 4096;
    if (total > 0) {
        buf->f_blocks = static_cast<fsblkcnt_t>(total / blockSize);
    }
    if (available >= 0 && total > 0) {
        if (available > total) available = total;
        buf->f_bfree = static_cast<fsblkcnt_t>(available / blockSize);
        buf->f_bavail = buf->f_bfree;
    }
    return rc;
}

int my_statvfs(const char* path, struct statvfs* buf) {
    int rc = orig_statvfs ? orig_statvfs(path, buf) : -1;
    if (rc != 0 || buf == nullptr || !IsDataPath(path)) return rc;

    long total = LookupLong("storage.total_bytes", -1);
    long available = LookupLong("storage.available_bytes", -1);
    long blockSize = buf->f_frsize > 0 ? static_cast<long>(buf->f_frsize)
                                       : static_cast<long>(buf->f_bsize);
    if (blockSize <= 0) blockSize = 4096;
    if (total > 0) {
        buf->f_blocks = static_cast<fsblkcnt_t>(total / blockSize);
    }
    if (available >= 0 && total > 0) {
        if (available > total) available = total;
        buf->f_bfree = static_cast<fsblkcnt_t>(available / blockSize);
        buf->f_bavail = buf->f_bfree;
    }
    return rc;
}

}  // namespace

void SetUptimeSpoofState(bool enabled, long long offsetSeconds) {
    g_uptime_offset_seconds.store(offsetSeconds, std::memory_order_relaxed);
    g_uptime_active.store(enabled, std::memory_order_release);
    DS_LOGW("audit:uptime state enabled=%d offset=%lld",
            enabled ? 1 : 0, offsetSeconds);
}

bool IsUptimeSpoofEnabled() {
    return g_uptime_active.load(std::memory_order_acquire);
}

long long GetUptimeOffsetSeconds() {
    return g_uptime_offset_seconds.load(std::memory_order_relaxed);
}

void InstallUptimeHooks() {
    if (!IsUptimeSpoofEnabled()) {
        DS_LOGW("audit:uptime hooks skipped state=off");
        return;
    }
    static std::atomic<bool> installed{false};
    if (installed.exchange(true)) {
        InstallPathGuardHooks(false);
        return;
    }

    RegisterNativeHookGroup("clock_gettime",
                            reinterpret_cast<void*>(&my_clock_gettime),
                            reinterpret_cast<void**>(&orig_clock_gettime),
                            nullptr);
    RegisterNativeHookGroup("sysinfo", reinterpret_cast<void*>(&my_sysinfo),
                            reinterpret_cast<void**>(&orig_sysinfo), "/libc.so");
    InstallPathGuardHooks(false);
    DS_LOGW("audit:uptime hooks registered offset=%lld mode=clock_gettime+sysinfo+path-guard",
            GetUptimeOffsetSeconds());
}

void InstallSystemHooks() {
    RegisterNativeHookGroup("uname", reinterpret_cast<void*>(&my_uname),
                            reinterpret_cast<void**>(&orig_uname), "/libc.so");
    RegisterNativeHookGroup("gethostname",
                            reinterpret_cast<void*>(&my_gethostname),
                            reinterpret_cast<void**>(&orig_gethostname),
                            "/libc.so");
    RegisterNativeHookGroup("getifaddrs",
                            reinterpret_cast<void*>(&my_getifaddrs),
                            reinterpret_cast<void**>(&orig_getifaddrs),
                            "/libc.so");
    RegisterNativeHookGroup("sysconf", reinterpret_cast<void*>(&my_sysconf),
                            reinterpret_cast<void**>(&orig_sysconf), "/libc.so");
    RegisterNativeHookGroup("sysinfo", reinterpret_cast<void*>(&my_sysinfo),
                            reinterpret_cast<void**>(&orig_sysinfo), "/libc.so");
    RegisterNativeHookGroup("sched_getaffinity",
                            reinterpret_cast<void*>(&my_sched_getaffinity),
                            reinterpret_cast<void**>(&orig_sched_getaffinity),
                            "/libc.so");
    RegisterNativeHookGroup("ioctl", reinterpret_cast<void*>(&my_ioctl),
                            reinterpret_cast<void**>(&orig_ioctl), "/libc.so");
    RegisterNativeHookGroup("statfs", reinterpret_cast<void*>(&my_statfs),
                            reinterpret_cast<void**>(&orig_statfs), "/libc.so");
    RegisterNativeHookGroup("statvfs", reinterpret_cast<void*>(&my_statvfs),
                            reinterpret_cast<void**>(&orig_statvfs), "/libc.so");
    DS_LOGW("audit:system hooks registered on all importer modules");
}

}  // namespace ds
