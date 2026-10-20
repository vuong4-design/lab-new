#include "ds_state.h"

#include <atomic>
#include <mutex>

namespace ds {

namespace {

void* (*orig_dlopen)(const char*, int) = nullptr;
void* (*orig_android_dlopen_ext)(const char*, int, const void*) = nullptr;

std::mutex g_recommit_mutex;
std::atomic<bool> g_in_recommit{false};
std::atomic<bool> g_recommit_pending{false};

void Recommit() {
    if (g_in_recommit.exchange(true)) {
        g_recommit_pending.store(true);
        return;
    }

    do {
        g_recommit_pending.store(false);
        std::lock_guard<std::mutex> lk(g_recommit_mutex);
        RegisterNativeHooksForPendingModules();
        bool ok = ZygiskPltHookCommit();
        DS_LOGW("audit:dlopen re-commit: ok=%d", ok);
    } while (g_recommit_pending.exchange(false));

    g_in_recommit.store(false);
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

void InstallDlopenHooks() {
    RegisterNativeHookGroup("dlopen", reinterpret_cast<void*>(&my_dlopen),
                            reinterpret_cast<void**>(&orig_dlopen), "/libdl.so");
    RegisterNativeHookGroup("android_dlopen_ext",
                            reinterpret_cast<void*>(&my_android_dlopen_ext),
                            reinterpret_cast<void**>(&orig_android_dlopen_ext),
                            "/libdl.so");
    DS_LOGW("audit:dlopen hooks registered on all importer modules");
}

}  // namespace ds
