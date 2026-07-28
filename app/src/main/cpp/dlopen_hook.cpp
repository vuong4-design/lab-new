#include "ds_state.h"

#include <atomic>
#include <dlfcn.h>
#include <mutex>

namespace ds {

namespace {

void* (*orig_dlopen)(const char*, int) = nullptr;
void* (*orig_android_dlopen_ext)(const char*, int, const void*) = nullptr;

std::mutex g_recommit_mutex;
std::atomic<bool> g_in_recommit{false};

void Recommit() {
    if (g_in_recommit.exchange(true)) return;
    {
        std::lock_guard<std::mutex> lk(g_recommit_mutex);
        bool ok = ZygiskPltHookCommit();
        DS_LOGW("audit:dlopen re-commit: ok=%d", ok);
    }
    g_in_recommit = false;
}

void* my_dlopen(const char* name, int flags) {
    void* h = orig_dlopen ? orig_dlopen(name, flags) : nullptr;
    if (h != nullptr) Recommit();
    return h;
}

void* my_android_dlopen_ext(const char* name, int flags, const void* extinfo) {
    void* h = orig_android_dlopen_ext
            ? orig_android_dlopen_ext(name, flags, extinfo)
            : nullptr;
    if (h != nullptr) Recommit();
    return h;
}

}  // namespace

void InstallDlopenHooks(dev_t dev, ino_t inode) {
    bool has_d = dlsym(RTLD_DEFAULT, "dlopen") != nullptr;
    bool has_ax = dlsym(RTLD_DEFAULT, "android_dlopen_ext") != nullptr;
    bool ok_d  = has_d && ZygiskPltHookRegister(dev, inode, "dlopen",
            reinterpret_cast<void*>(&my_dlopen),
            reinterpret_cast<void**>(&orig_dlopen));
    bool ok_ax = has_ax && ZygiskPltHookRegister(dev, inode, "android_dlopen_ext",
            reinterpret_cast<void*>(&my_android_dlopen_ext),
            reinterpret_cast<void**>(&orig_android_dlopen_ext));
    DS_LOGW("audit:dlopen hooks: dlopen=%d/%d android_dlopen_ext=%d/%d",
            ok_d, has_d, ok_ax, has_ax);
}

}  // namespace ds
