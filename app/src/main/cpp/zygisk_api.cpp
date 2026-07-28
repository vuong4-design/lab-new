#include "ds_state.h"
#include "zygisk.hpp"

#include <utility>

namespace ds {
namespace {

zygisk::Api* g_api = nullptr;
std::string g_module_dir;

}  // namespace

void SetZygiskApi(void* api) {
    g_api = reinterpret_cast<zygisk::Api*>(api);
}

void SetModuleDir(std::string dir) {
    g_module_dir = std::move(dir);
}

std::string GetModuleDir() {
    return g_module_dir;
}

bool ZygiskPltHookRegister(dev_t dev, ino_t inode, const char* symbol,
                           void* new_func, void** old_func) {
    if (g_api == nullptr || symbol == nullptr || new_func == nullptr) {
        return false;
    }
    g_api->pltHookRegister(dev, inode, symbol, new_func, old_func);
    return true;
}

bool ZygiskPltHookCommit() {
    return g_api != nullptr && g_api->pltHookCommit();
}

}  // namespace ds
