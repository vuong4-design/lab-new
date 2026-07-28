#include "ds_state.h"

#include <sys/sysmacros.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace ds {

namespace {

int  (*orig_sp_get)(const char*, char*) = nullptr;

const prop_info* (*orig_sp_find)(const char*) = nullptr;

int  (*orig_sp_read)(const prop_info*, char*, char*) = nullptr;

void (*orig_sp_read_callback)(
        const prop_info*,
        void (*)(void*, const char*, const char*, uint32_t),
        void*) = nullptr;

// Opaque prop_info* we return from __system_property_find for spoofed keys.
// The real layout is libc-internal; our read hook recognises these pointers
// and short-circuits before they reach the real read code.
struct SyntheticPi {
    std::string name;
};

std::mutex g_synth_mutex;
std::vector<std::unique_ptr<SyntheticPi>> g_synth_storage;
std::unordered_map<const prop_info*, SyntheticPi*> g_synth_index;
std::unordered_map<std::string, const prop_info*> g_synth_by_name;

bool IsSynthetic(const prop_info* pi, std::string& nameOut) {
    if (pi == nullptr) return false;
    std::lock_guard<std::mutex> lk(g_synth_mutex);
    auto it = g_synth_index.find(pi);
    if (it == g_synth_index.end()) return false;
    nameOut = it->second->name;
    return true;
}

const prop_info* MakeSynthetic(const char* name) {
    if (name == nullptr) return nullptr;
    std::lock_guard<std::mutex> lk(g_synth_mutex);
    auto existing = g_synth_by_name.find(name);
    if (existing != g_synth_by_name.end()) {
        return existing->second;
    }

    auto p = std::make_unique<SyntheticPi>();
    p->name = name;
    const prop_info* opaque = reinterpret_cast<const prop_info*>(p.get());

    g_synth_index.emplace(opaque, p.get());
    g_synth_by_name.emplace(p->name, opaque);
    g_synth_storage.push_back(std::move(p));
    return opaque;
}

int my_sp_get(const char* name, char* value) {
    std::string spoofed;
    if (LookupProperty(name, spoofed)) {
        size_t n = spoofed.size();
        if (n > PROP_VALUE_MAX - 1) n = PROP_VALUE_MAX - 1;
        if (value != nullptr) {
            memcpy(value, spoofed.data(), n);
            value[n] = '\0';
        }
        return (int)n;
    }
    if (orig_sp_get) return orig_sp_get(name, value);
    return 0;
}

const prop_info* my_sp_find(const char* name) {
    if (name != nullptr) {
        std::string spoofed;
        if (LookupProperty(name, spoofed)) {
            return MakeSynthetic(name);
        }
    }
    if (orig_sp_find) return orig_sp_find(name);
    return nullptr;
}

int my_sp_read(const prop_info* pi, char* name, char* value) {
    std::string synthName;
    if (IsSynthetic(pi, synthName)) {
        std::string spoofed;
        if (LookupProperty(synthName.c_str(), spoofed)) {
            if (name != nullptr) {
                size_t nlen = synthName.size();
                if (nlen > PROP_NAME_MAX - 1) nlen = PROP_NAME_MAX - 1;
                memcpy(name, synthName.data(), nlen);
                name[nlen] = '\0';
            }
            size_t vlen = spoofed.size();
            if (vlen > PROP_VALUE_MAX - 1) vlen = PROP_VALUE_MAX - 1;
            if (value != nullptr) {
                memcpy(value, spoofed.data(), vlen);
                value[vlen] = '\0';
            }
            return (int)vlen;
        }
    }

    int rc = 0;
    if (orig_sp_read != nullptr) {
        rc = orig_sp_read(pi, name, value);
    }
    if (name != nullptr && value != nullptr) {
        std::string spoofed;
        if (LookupProperty(name, spoofed)) {
            size_t vlen = spoofed.size();
            if (vlen > PROP_VALUE_MAX - 1) vlen = PROP_VALUE_MAX - 1;
            memcpy(value, spoofed.data(), vlen);
            value[vlen] = '\0';
            return (int)vlen;
        }
    }
    return rc;
}

// read_callback invokes the user callback synchronously, so this can live on
// the caller's stack.
struct CbWrapper {
    void (*orig)(void*, const char*, const char*, uint32_t);
    void* orig_cookie;
    const char* synth_name;
};

void CbTrampoline(void* cookie, const char* name, const char* value,
                  uint32_t serial) {
    auto* w = reinterpret_cast<CbWrapper*>(cookie);
    const char* effective_name = (w->synth_name != nullptr) ? w->synth_name : name;

    std::string spoofed;
    if (LookupProperty(effective_name, spoofed)) {
        w->orig(w->orig_cookie, effective_name, spoofed.c_str(), serial);
        return;
    }
    w->orig(w->orig_cookie, name, value, serial);
}

void my_sp_read_callback(const prop_info* pi,
                         void (*callback)(void*, const char*, const char*,
                                          uint32_t),
                         void* cookie) {
    if (callback == nullptr) {
        if (orig_sp_read_callback) orig_sp_read_callback(pi, callback, cookie);
        return;
    }

    std::string synthName;
    if (IsSynthetic(pi, synthName)) {
        std::string spoofed;
        if (LookupProperty(synthName.c_str(), spoofed)) {
            callback(cookie, synthName.c_str(), spoofed.c_str(), /*serial=*/0);
            return;
        }
    }

    CbWrapper w{};
    w.orig = callback;
    w.orig_cookie = cookie;
    w.synth_name = nullptr;

    if (orig_sp_read_callback) {
        orig_sp_read_callback(pi, &CbTrampoline, &w);
    }
}

struct LibLocation {
    bool found;
    dev_t dev;
    ino_t inode;
    std::string path;
};

LibLocation FindLibrary(const char* suffix) {
    LibLocation result{false, 0, 0, {}};
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return result;

    std::string suf = suffix;
    std::string line;
    while (std::getline(maps, line)) {
        uintptr_t start = 0;
        uintptr_t end = 0;
        unsigned int devMajor = 0;
        unsigned int devMinor = 0;
        unsigned long inode = 0;
        char perms[5] = {};
        unsigned long offset = 0;
        char pathBuf[512] = {};

        int parsed = sscanf(line.c_str(),
                            "%lx-%lx %4s %lx %x:%x %lu %511[^\n]",
                            &start, &end, perms, &offset, &devMajor,
                            &devMinor, &inode, pathBuf);
        if (parsed < 8) continue;

        std::string path(pathBuf);
        auto first = path.find_first_not_of(' ');
        if (first != std::string::npos) path = path.substr(first);
        if (path.size() < suf.size()) continue;

        auto pos = path.rfind(suf);
        if (pos != std::string::npos && pos + suf.size() == path.size()) {
            result.found = true;
            result.dev = makedev(devMajor, devMinor);
            result.inode = static_cast<ino_t>(inode);
            result.path = path;
            return result;
        }
    }
    return result;
}

}  // namespace

void InstallPropertyHooks(bool installOptionalHooks) {
    InstallPropertyHooks(installOptionalHooks, installOptionalHooks);
}

void InstallPropertyHooks(bool installSystemHookGroup,
                          bool installDlopenHookGroup) {
    InstallPropertyHooks(/*installPropertyGet=*/true,
                         /*installPropertyFind=*/true,
                         /*installPropertyReadCallback=*/true,
                         installSystemHookGroup,
                         installDlopenHookGroup);
}

void InstallPropertyHooks(bool installPropertyGet,
                          bool installPropertyFind,
                          bool installPropertyReadCallback,
                          bool installSystemHookGroup,
                          bool installDlopenHookGroup) {
    LibLocation libc = FindLibrary("/libc.so");
    if (!libc.found) {
        DS_LOGE("InstallPropertyHooks: libc.so not found in /proc/self/maps");
        return;
    }
    DS_LOGW("audit:Located libc at %s (dev=%lu inode=%lu)", libc.path.c_str(),
            (unsigned long)libc.dev, (unsigned long)libc.inode);

    bool ok_get = false;
    if (installPropertyGet) {
        ok_get = ZygiskPltHookRegister(
            libc.dev, libc.inode, "__system_property_get",
            reinterpret_cast<void*>(&my_sp_get),
            reinterpret_cast<void**>(&orig_sp_get));
    }
    bool ok_find = false;
    if (installPropertyFind) {
        ok_find = ZygiskPltHookRegister(
            libc.dev, libc.inode, "__system_property_find",
            reinterpret_cast<void*>(&my_sp_find),
            reinterpret_cast<void**>(&orig_sp_find));
    }
    bool has_read = dlsym(RTLD_DEFAULT, "__system_property_read") != nullptr;
    // __system_property_read is not present as a PLT relocation on many modern
    // Android processes. Registering it can make the whole Zygisk commit fail,
    // so rely on get/find/read_callback for the core property path.
    bool ok_read = false;
    bool ok_cb = false;
    if (installPropertyReadCallback) {
        ok_cb = ZygiskPltHookRegister(
            libc.dev, libc.inode, "__system_property_read_callback",
            reinterpret_cast<void*>(&my_sp_read_callback),
            reinterpret_cast<void**>(&orig_sp_read_callback));
    }

    DS_LOGW("audit:RegisterHook(prop): req_get=%d req_find=%d req_cb=%d get=%d find=%d read=%d read_exported=%d read_callback=%d",
            installPropertyGet, installPropertyFind,
            installPropertyReadCallback, ok_get, ok_find, ok_read,
            has_read, ok_cb);

    bool coreCommitted = ZygiskPltHookCommit();
    DS_LOGW("audit:CommitHook(core)=%d spoofed_keys=%zu orig_get=%p orig_find=%p "
            "orig_read=%p orig_cb=%p",
            coreCommitted, (size_t)g_props.size(), (void*)orig_sp_get,
            (void*)orig_sp_find, (void*)orig_sp_read,
            (void*)orig_sp_read_callback);

    if (!installSystemHookGroup && !installDlopenHookGroup) {
        DS_LOGW("audit:native optional hooks skipped; property core only");
        return;
    }

    if (installSystemHookGroup) {
        InstallSystemHooks(libc.dev, libc.inode);
        // /proc + /sys read guard rides in the same commit batch below.
        InstallPathGuardHooks();
    } else {
        DS_LOGW("audit:system hooks skipped by native mode");
    }

    if (installDlopenHookGroup) {
        LibLocation libdl = FindLibrary("/libdl.so");
        if (libdl.found) {
            DS_LOGW("audit:Located libdl at %s (dev=%lu inode=%lu)", libdl.path.c_str(),
                    (unsigned long)libdl.dev, (unsigned long)libdl.inode);
            InstallDlopenHooks(libdl.dev, libdl.inode);
        } else {
            DS_LOGW("libdl.so not found - dlopen re-hook skipped");
        }
    } else {
        DS_LOGW("audit:dlopen hooks skipped by native mode");
    }

    bool optionalCommitted = ZygiskPltHookCommit();
    DS_LOGW("audit:CommitHook(optional)=%d spoofed_keys=%zu system=%d dlopen=%d",
            optionalCommitted, (size_t)g_props.size(),
            installSystemHookGroup, installDlopenHookGroup);
}

}  // namespace ds
