#include "ds_state.h"

#include <cstdio>
#include <cstring>
#include <fstream>
#include <set>
#include <sys/sysmacros.h>

namespace ds {
namespace {

struct ModuleInfo {
    dev_t dev;
    ino_t inode;
    std::string path;
};

struct HookGroup {
    std::string symbol;
    void* hook;
    void** original;
    std::string provider_suffix;
};

std::mutex g_registry_mutex;
std::vector<ModuleInfo> g_modules;
std::set<std::pair<unsigned long, unsigned long>> g_module_keys;
std::vector<HookGroup> g_groups;
std::set<std::pair<size_t, unsigned long long>> g_registered;
std::vector<void (*)(dev_t, ino_t)> g_module_callbacks;

unsigned long long ModuleKey(dev_t dev, ino_t inode) {
    return (static_cast<unsigned long long>(
                static_cast<unsigned long>(dev)) << 32)
            ^ static_cast<unsigned long long>(inode);
}

bool EndsWith(const std::string& value, const std::string& suffix) {
    return !suffix.empty() && value.size() >= suffix.size()
            && value.compare(value.size() - suffix.size(), suffix.size(),
                             suffix) == 0;
}

bool IsExcludedModule(const std::string& path) {
    if (path.empty()) return true;
    if (path.find("/data/adb/") != std::string::npos) return true;
    if (path.find("devicespooflab") != std::string::npos) return true;
    if (path.find("libds_") != std::string::npos) return true;
    if (path.find("libperfetto_android") != std::string::npos) return true;
    if (path.find("liblsplant") != std::string::npos) return true;
    if (path.find("/memfd:") != std::string::npos) return true;
    return false;
}

void ScanModulesLocked() {
    std::ifstream maps("/proc/self/maps");
    if (!maps.is_open()) return;

    std::string line;
    while (std::getline(maps, line)) {
        unsigned long start = 0;
        unsigned long end = 0;
        unsigned long offset = 0;
        unsigned long inode = 0;
        unsigned int dev_major = 0;
        unsigned int dev_minor = 0;
        char perms[5] = {};
        char path_buf[512] = {};

        int parsed = sscanf(line.c_str(),
                            "%lx-%lx %4s %lx %x:%x %lu %511[^\n]",
                            &start, &end, perms, &offset, &dev_major,
                            &dev_minor, &inode, path_buf);
        if (parsed < 8) continue;
        if (perms[2] != 'x') continue;
        if (inode == 0) continue;

        std::string path(path_buf);
        size_t first = path.find_first_not_of(' ');
        if (first == std::string::npos) continue;
        path = path.substr(first);
        if (IsExcludedModule(path)) continue;

        dev_t dev = makedev(dev_major, dev_minor);
        auto key = std::make_pair(static_cast<unsigned long>(dev),
                                  static_cast<unsigned long>(inode));
        if (!g_module_keys.insert(key).second) continue;
        g_modules.push_back(ModuleInfo{dev, static_cast<ino_t>(inode), path});
    }
}

void RegisterGroupOnModuleLocked(size_t group_index,
                                 const ModuleInfo& module) {
    const HookGroup& group = g_groups[group_index];
    if (!group.provider_suffix.empty()
            && EndsWith(module.path, group.provider_suffix)) {
        return;
    }

    auto key = std::make_pair(group_index, ModuleKey(module.dev, module.inode));
    if (g_registered.find(key) != g_registered.end()) return;

    if (ZygiskPltHookRegister(module.dev, module.inode, group.symbol.c_str(),
                              group.hook, group.original)) {
        g_registered.insert(key);
    }
}

void RegisterModuleLocked(const ModuleInfo& module) {
    for (size_t i = 0; i < g_groups.size(); ++i) {
        RegisterGroupOnModuleLocked(i, module);
    }
}

}  // namespace

void RegisterNativeHookGroup(const char* symbol, void* hook, void** original,
                             const char* provider_suffix) {
    if (symbol == nullptr || hook == nullptr || original == nullptr) return;

    std::lock_guard<std::mutex> lock(g_registry_mutex);
    std::string provider = provider_suffix == nullptr
            ? std::string() : std::string(provider_suffix);
    for (const HookGroup& existing : g_groups) {
        if (existing.symbol == symbol && existing.hook == hook
                && existing.original == original
                && existing.provider_suffix == provider) {
            return;
        }
    }
    g_groups.push_back(HookGroup{symbol, hook, original, provider});
    size_t index = g_groups.size() - 1;
    for (const ModuleInfo& module : g_modules) {
        RegisterGroupOnModuleLocked(index, module);
    }
}

void RegisterNativeModuleCallback(void (*callback)(dev_t, ino_t)) {
    if (callback == nullptr) return;

    std::vector<ModuleInfo> modules;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        ScanModulesLocked();
        for (auto existing : g_module_callbacks) {
            if (existing == callback) return;
        }
        g_module_callbacks.push_back(callback);
        modules = g_modules;
    }

    // Run callbacks outside the registry lock: they may call back into the
    // Zygisk API and must not hold g_registry_mutex.
    for (const ModuleInfo& module : modules) {
        callback(module.dev, module.inode);
    }
}

void RegisterNativeHooksForPendingModules() {
    std::vector<ModuleInfo> new_modules;
    std::vector<void (*)(dev_t, ino_t)> callbacks;

    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        size_t before = g_modules.size();
        ScanModulesLocked();
        for (size_t i = before; i < g_modules.size(); ++i) {
            RegisterModuleLocked(g_modules[i]);
        }
        new_modules.assign(g_modules.begin() + static_cast<long>(before),
                           g_modules.end());
        callbacks = g_module_callbacks;
    }

    for (const ModuleInfo& module : new_modules) {
        for (auto callback : callbacks) {
            callback(module.dev, module.inode);
        }
    }
}

std::vector<std::pair<dev_t, ino_t>> GetNativeLoadedModules() {
    std::vector<std::pair<dev_t, ino_t>> result;
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    ScanModulesLocked();
    result.reserve(g_modules.size());
    for (const ModuleInfo& module : g_modules) {
        result.emplace_back(module.dev, module.inode);
    }
    return result;
}

}  // namespace ds
