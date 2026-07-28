#include "ds_state.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <string>
#include <vector>
#include <fstream>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <unistd.h>

// ---------------------------------------------------------------------------
// Native /proc and /sys read guard.
//
// Even after helper libraries are loaded from an anonymous memfd, a hostile app
// can still read *its own* process files directly through libc and see:
//   * executable mappings under /data/adb/modules/... or /memfd:<tag>
//     (/proc/self/maps, /proc/self/smaps, /proc/self/task/<tid>/maps),
//   * file descriptors that resolve to the module directory
//     (readlink of /proc/self/fd/<n>),
//   * hardware facts (RAM / MAC) that contradict the Java-level spoof
//     (/proc/meminfo, /sys/class/net/<if>/address).
//
// This guard PLT-hooks the file entry points (open/openat/open64/fopen/
// fopen64/readlink/readlinkat) in *every* currently-loaded executable module.
// For a narrow allow-list of sensitive paths it returns a sanitised in-memory
// copy (a memfd) instead of the real file; everything else is passed straight
// through to the original function. All internal reads use RAW syscalls so the
// guard can never recurse into its own hooks, and every error path falls back
// to the original libc behaviour so normal file I/O never regresses.
// ---------------------------------------------------------------------------

namespace ds {
namespace {

using OpenFn       = int (*)(const char*, int, ...);
using OpenatFn     = int (*)(int, const char*, int, ...);
using FopenFn      = FILE* (*)(const char*, const char*);
using ReadlinkFn   = ssize_t (*)(const char*, char*, size_t);
using ReadlinkatFn = ssize_t (*)(int, const char*, char*, size_t);

OpenFn       orig_open       = nullptr;
OpenFn       orig_open64     = nullptr;
OpenatFn     orig_openat     = nullptr;
FopenFn      orig_fopen      = nullptr;
FopenFn      orig_fopen64    = nullptr;
ReadlinkFn   orig_readlink   = nullptr;
ReadlinkatFn orig_readlinkat = nullptr;

// ---- raw syscall helpers (bypass the PLT we are about to hook) --------------
int raw_open(const char* path, int flags) {
#if defined(__NR_openat)
    return static_cast<int>(syscall(__NR_openat, AT_FDCWD, path, flags, 0));
#else
    return static_cast<int>(syscall(__NR_open, path, flags, 0));
#endif
}
ssize_t raw_read(int fd, void* buf, size_t n) {
    return static_cast<ssize_t>(syscall(__NR_read, fd, buf, n));
}
void raw_close(int fd) { syscall(__NR_close, fd); }
ssize_t raw_write(int fd, const void* buf, size_t n) {
    return static_cast<ssize_t>(syscall(__NR_write, fd, buf, n));
}
int raw_memfd(const char* name) {
#if defined(__NR_memfd_create)
    return static_cast<int>(syscall(__NR_memfd_create, name, MFD_CLOEXEC));
#else
    (void)name;
    return -1;
#endif
}

std::string ReadWholeFileRaw(const char* path) {
    std::string data;
    int fd = raw_open(path, O_RDONLY);
    if (fd < 0) return data;
    char buf[1 << 14];
    for (;;) {
        ssize_t r = raw_read(fd, buf, sizeof(buf));
        if (r == 0) break;
        if (r < 0) { if (errno == EINTR) continue; break; }
        data.append(buf, static_cast<size_t>(r));
    }
    raw_close(fd);
    return data;
}

// Materialise `content` into an anonymous memfd and rewind it for reading.
int MakeContentFd(const std::string& content, const char* tag) {
    int fd = raw_memfd(tag);
    if (fd < 0) return -1;
    size_t off = 0;
    while (off < content.size()) {
        ssize_t w = raw_write(fd, content.data() + off, content.size() - off);
        if (w < 0) { if (errno == EINTR) continue; raw_close(fd); return -1; }
        off += static_cast<size_t>(w);
    }
    syscall(__NR_lseek, fd, 0, SEEK_SET);
    return fd;
}

bool Contains(const std::string& hay, const char* needle) {
    return hay.find(needle) != std::string::npos;
}

// A maps/smaps line (or readlink target) is "sensitive" when it points at the
// module directory, a memfd we created, or one of our helper sonames.
bool IsSensitive(const std::string& s) {
    return Contains(s, "/data/adb/")
        || Contains(s, "devicespooflab")
        || Contains(s, "liblsplant")
        || Contains(s, "libds_java_hooks")
        || Contains(s, "/memfd:")
        || Contains(s, "lsplant");
}

// True for a /proc/.../maps or /smaps header line "<hex>-<hex> perms ...".
bool IsMapsHeader(const std::string& line) {
    size_t dash = line.find('-');
    if (dash == std::string::npos || dash == 0 || dash > 32) return false;
    for (size_t i = 0; i < dash; ++i) {
        char c = line[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
              (c >= 'A' && c <= 'F')))
            return false;
    }
    return true;
}

// Beyond name-based leaks, the generic *shape* of injected native code is an
// executable mapping not backed by a real on-disk file: memfd copies of our
// helper libraries and inline-hook trampolines that surface as "[anonymous]"
// or "(deleted)". Legitimate library text is file-backed (/system, /apex,
// /vendor, the app lib dir), so we drop executable, non-file-backed blocks.
bool IsInjectionShape(const std::string& header) {
    size_t sp = header.find(' ');
    if (sp == std::string::npos || sp + 4 > header.size()) return false;
    const char* perms = header.c_str() + sp + 1;   // "rwxp"
    if (perms[2] != 'x') return false;             // executable mappings only
    if (header.find("(deleted)") != std::string::npos) return true;
    if (header.find("/memfd:") != std::string::npos) return true;
    if (header.find("[anon") != std::string::npos) return true;  // [anonymous]/[anon:...]
    // Executable, inode 0, and no trailing pathname => anonymous exec page.
    unsigned long a = 0, b = 0, off = 0;
    unsigned int maj = 0, min = 0;
    unsigned long ino = 0;
    char pr[5] = {};
    char pathBuf[8] = {};
    int n = sscanf(header.c_str(), "%lx-%lx %4s %lx %x:%x %lu %7s",
                   &a, &b, pr, &off, &maj, &min, &ino, pathBuf);
    return n == 7 && ino == 0;  // parsed through inode with nothing after = no path
}

// Drop the whole mapping block (header + any smaps detail lines) for sensitive
// entries; keep everything else verbatim.
std::string FilterMaps(const std::string& raw) {
    std::string out;
    out.reserve(raw.size());
    size_t i = 0;
    bool dropBlock = false;
    while (i < raw.size()) {
        size_t nl = raw.find('\n', i);
        bool hasNl = (nl != std::string::npos);
        if (!hasNl) nl = raw.size();
        std::string line = raw.substr(i, nl - i);
        if (IsMapsHeader(line)) dropBlock = IsSensitive(line) || IsInjectionShape(line);
        if (!dropBlock) {
            out += line;
            if (hasNl) out += '\n';
        }
        i = nl + 1;
    }
    return out;
}

// Rewrite MemTotal / MemAvailable when the profile supplies overrides; returns
// empty string when there is nothing to override (=> fall through to real file).
std::string FilterMeminfo(const std::string& raw) {
    std::string total, avail;
    bool haveTotal = LookupProperty("mem.total_kb", total) && !total.empty();
    bool haveAvail = LookupProperty("mem.available_kb", avail) && !avail.empty();
    if (!haveTotal && !haveAvail) return std::string();
    std::string out;
    size_t i = 0;
    while (i < raw.size()) {
        size_t nl = raw.find('\n', i);
        bool hasNl = (nl != std::string::npos);
        if (!hasNl) nl = raw.size();
        std::string line = raw.substr(i, nl - i);
        if (haveTotal && line.rfind("MemTotal:", 0) == 0) {
            line = "MemTotal:       " + total + " kB";
        } else if (haveAvail && line.rfind("MemAvailable:", 0) == 0) {
            line = "MemAvailable:   " + avail + " kB";
        }
        out += line;
        if (hasNl) out += '\n';
        i = nl + 1;
    }
    return out;
}

const char* kFdTag = "dalvik-jit-code-cache";

// Returns >=0 fd with sanitised content, or -1 to fall through to the original.
int SanitisedFdFor(const char* path) {
    if (path == nullptr) return -1;
    std::string p(path);

    if (p.rfind("/proc/", 0) == 0 &&
        (Contains(p, "/maps") || Contains(p, "/smaps"))) {
        std::string raw = ReadWholeFileRaw(path);
        if (raw.empty()) return -1;
        return MakeContentFd(FilterMaps(raw), kFdTag);
    }
    if (p == "/proc/meminfo") {
        std::string raw = ReadWholeFileRaw(path);
        if (raw.empty()) return -1;
        std::string filtered = FilterMeminfo(raw);
        if (filtered.empty()) return -1;
        return MakeContentFd(filtered, kFdTag);
    }
    if (p.rfind("/sys/class/net/", 0) == 0 && p.size() > 8 &&
        p.compare(p.size() - 8, 8, "/address") == 0) {
        std::string iface = p.substr(15, p.size() - 15 - 8);
        std::string mac;
        if (iface.rfind("wlan", 0) == 0) {
            if (LookupProperty("wifi.mac", mac) && !mac.empty())
                return MakeContentFd(mac + "\n", kFdTag);
        } else if (iface.rfind("bt", 0) == 0 || iface.rfind("bnep", 0) == 0) {
            if (LookupProperty("bluetooth.mac", mac) && !mac.empty())
                return MakeContentFd(mac + "\n", kFdTag);
        }
        return -1;
    }
    return -1;
}

bool OpenIsReadOnly(int flags) {
    return (flags & (O_WRONLY | O_RDWR | O_CREAT)) == 0;
}

// ---- hooked entry points ----------------------------------------------------
int my_openat(int dirfd, const char* path, int flags, ...) {
    if (path && OpenIsReadOnly(flags)) {
        int fd = SanitisedFdFor(path);
        if (fd >= 0) return fd;
    }
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags); mode = static_cast<mode_t>(va_arg(ap, int)); va_end(ap);
    }
    return orig_openat ? orig_openat(dirfd, path, flags, mode) : -1;
}

int OpenImpl(OpenFn orig, const char* path, int flags, mode_t mode) {
    if (path && OpenIsReadOnly(flags)) {
        int fd = SanitisedFdFor(path);
        if (fd >= 0) return fd;
    }
    return orig ? orig(path, flags, mode) : -1;
}
int my_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = static_cast<mode_t>(va_arg(ap, int)); va_end(ap); }
    return OpenImpl(orig_open, path, flags, mode);
}
int my_open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = static_cast<mode_t>(va_arg(ap, int)); va_end(ap); }
    return OpenImpl(orig_open64, path, flags, mode);
}

FILE* FopenImpl(FopenFn orig, const char* path, const char* mode) {
    if (path && mode && mode[0] == 'r') {
        int fd = SanitisedFdFor(path);
        if (fd >= 0) {
            FILE* f = fdopen(fd, mode);
            if (f != nullptr) return f;
            close(fd);
        }
    }
    return orig ? orig(path, mode) : nullptr;
}
FILE* my_fopen(const char* path, const char* mode)   { return FopenImpl(orig_fopen, path, mode); }
FILE* my_fopen64(const char* path, const char* mode) { return FopenImpl(orig_fopen64, path, mode); }

ssize_t MaskLink(ssize_t n, char* buf, size_t bufsiz) {
    if (n > 0) {
        std::string s(buf, static_cast<size_t>(n));
        if (IsSensitive(s)) {
            static const char kFake[] =
                "/data/dalvik-cache/arm64/system@framework@boot.art";
            size_t fl = sizeof(kFake) - 1;
            if (fl > bufsiz) fl = bufsiz;
            memcpy(buf, kFake, fl);
            return static_cast<ssize_t>(fl);
        }
    }
    return n;
}
ssize_t my_readlink(const char* path, char* buf, size_t bufsiz) {
    ssize_t n = orig_readlink ? orig_readlink(path, buf, bufsiz) : -1;
    return MaskLink(n, buf, bufsiz);
}
ssize_t my_readlinkat(int dirfd, const char* path, char* buf, size_t bufsiz) {
    ssize_t n = orig_readlinkat ? orig_readlinkat(dirfd, path, buf, bufsiz) : -1;
    return MaskLink(n, buf, bufsiz);
}

// dl_iterate_phdr enumerates every loaded ELF; detectors use it to read each
// module's soname (e.g. "liblsplant.so") without touching /proc. We wrap the
// caller's callback and silently skip entries whose dlpi_name is sensitive, so
// our helper libraries and memfd images disappear from the enumeration too.
using DlIterFn = int (*)(int (*)(struct dl_phdr_info*, size_t, void*), void*);
DlIterFn orig_dl_iterate_phdr = nullptr;

struct DlIterCtx {
    int (*cb)(struct dl_phdr_info*, size_t, void*);
    void* data;
};
int DlIterTrampoline(struct dl_phdr_info* info, size_t size, void* data) {
    auto* ctx = static_cast<DlIterCtx*>(data);
    if (info && info->dlpi_name && IsSensitive(info->dlpi_name)) return 0;  // skip
    return ctx->cb(info, size, ctx->data);
}
int my_dl_iterate_phdr(int (*cb)(struct dl_phdr_info*, size_t, void*), void* data) {
    if (!orig_dl_iterate_phdr) return 0;
    DlIterCtx ctx{cb, data};
    return orig_dl_iterate_phdr(&DlIterTrampoline, &ctx);
}

void RegisterOnModule(dev_t dev, ino_t inode) {
    if (dlsym(RTLD_DEFAULT, "openat"))
        ZygiskPltHookRegister(dev, inode, "openat", reinterpret_cast<void*>(&my_openat), reinterpret_cast<void**>(&orig_openat));
    if (dlsym(RTLD_DEFAULT, "open"))
        ZygiskPltHookRegister(dev, inode, "open", reinterpret_cast<void*>(&my_open), reinterpret_cast<void**>(&orig_open));
    if (dlsym(RTLD_DEFAULT, "open64"))
        ZygiskPltHookRegister(dev, inode, "open64", reinterpret_cast<void*>(&my_open64), reinterpret_cast<void**>(&orig_open64));
    if (dlsym(RTLD_DEFAULT, "fopen"))
        ZygiskPltHookRegister(dev, inode, "fopen", reinterpret_cast<void*>(&my_fopen), reinterpret_cast<void**>(&orig_fopen));
    if (dlsym(RTLD_DEFAULT, "fopen64"))
        ZygiskPltHookRegister(dev, inode, "fopen64", reinterpret_cast<void*>(&my_fopen64), reinterpret_cast<void**>(&orig_fopen64));
    if (dlsym(RTLD_DEFAULT, "readlink"))
        ZygiskPltHookRegister(dev, inode, "readlink", reinterpret_cast<void*>(&my_readlink), reinterpret_cast<void**>(&orig_readlink));
    if (dlsym(RTLD_DEFAULT, "readlinkat"))
        ZygiskPltHookRegister(dev, inode, "readlinkat", reinterpret_cast<void*>(&my_readlinkat), reinterpret_cast<void**>(&orig_readlinkat));
    if (dlsym(RTLD_DEFAULT, "dl_iterate_phdr"))
        ZygiskPltHookRegister(dev, inode, "dl_iterate_phdr", reinterpret_cast<void*>(&my_dl_iterate_phdr), reinterpret_cast<void**>(&orig_dl_iterate_phdr));
}

// Walk /proc/self/maps and register the guard on every distinct (dev,inode)
// that backs an executable, file-backed mapping (i.e. every loaded module),
// so that app-direct calls to open()/readlink() are intercepted too.
void ForEachLoadedModule() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return;
    std::string line;
    std::vector<std::pair<unsigned long, unsigned long>> seen;
    while (std::getline(maps, line)) {
        unsigned long start = 0, end = 0, off = 0;
        unsigned int maj = 0, min = 0;
        unsigned long ino = 0;
        char perms[5] = {};
        char pathBuf[512] = {};
        int parsed = sscanf(line.c_str(),
                            "%lx-%lx %4s %lx %x:%x %lu %511[^\n]",
                            &start, &end, perms, &off, &maj, &min, &ino, pathBuf);
        if (parsed < 8) continue;
        if (perms[2] != 'x') continue;   // executable segments only
        if (ino == 0) continue;          // skip anonymous mappings
        dev_t dev = makedev(maj, min);
        unsigned long devKey = static_cast<unsigned long>(dev);
        bool dup = false;
        for (auto& s : seen) if (s.first == devKey && s.second == ino) { dup = true; break; }
        if (dup) continue;
        seen.emplace_back(devKey, ino);
        RegisterOnModule(dev, static_cast<ino_t>(ino));
    }
}

// Identify system_server via a RAW read of /proc/self/cmdline (never through
// the libc we are about to hook). Intercepting open()/openat() inside
// system_server is both unnecessary (a detector inspects its *own* app
// process, not system_server) and catastrophic: a single bad interception
// there crash-loops the whole device. We refuse to guard it, always.
bool IsSystemServer() {
    char buf[64] = {};
    int fd = raw_open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return false;
    ssize_t n = raw_read(fd, buf, sizeof(buf) - 1);
    raw_close(fd);
    if (n <= 0) return false;
    // cmdline is NUL-delimited; the first field is the process name.
    return std::string(buf) == "system_server";
}

}  // namespace

void InstallPathGuardHooks() {
    // OPT-IN ONLY. This guard PLT-hooks the hot open()/openat()/fopen() family
    // across every loaded module; a fault anywhere on that path brings down the
    // whole process. It now stays OFF unless the profile explicitly sets
    // native.guard=on, so a stock install can never boot-loop because of it.
    std::string flag;
    if (!(LookupProperty("native.guard", flag) && flag == "on")) {
        DS_LOGW("audit:path guard OFF (set native.guard=on to enable; app processes only)");
        return;
    }
    // Hard refusal in system_server regardless of the flag.
    if (IsSystemServer()) {
        DS_LOGW("audit:path guard refused in system_server (unsafe/unneeded)");
        return;
    }
    // Registers hooks only; the shared ZygiskPltHookCommit() in
    // InstallPropertyHooks() commits this batch together with the others.
    ForEachLoadedModule();
    DS_LOGW("audit:path guard registered (app process only; maps/smaps/meminfo/sys-net/readlink)");
}

}  // namespace ds
