#pragma once

#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>
#include <sys/types.h>
#include <jni.h>
#include <android/log.h>

#define DS_LOG_TAG "DeviceSpoofLab-Native"
#define DS_LOGI(...) do { if (::ds::IsVerboseLoggingEnabled()) __android_log_print(ANDROID_LOG_INFO, DS_LOG_TAG, __VA_ARGS__); } while (0)
#define DS_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  DS_LOG_TAG, __VA_ARGS__)
#define DS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DS_LOG_TAG, __VA_ARGS__)

namespace ds {

// Populated once from Java; read-only after install.
extern std::unordered_map<std::string, std::string> g_props;

bool LookupProperty(const char* name, std::string& out);

bool IsVerboseLoggingEnabled();

void SetZygiskApi(void* api);

void SetModuleDir(std::string dir);

std::string GetModuleDir();

bool ZygiskPltHookRegister(dev_t dev, ino_t inode, const char* symbol,
                           void* new_func, void** old_func);

bool ZygiskPltHookCommit();

void RegisterNativeHookGroup(const char* symbol, void* hook, void** original,
                             const char* provider_suffix);

void RegisterNativeModuleCallback(void (*callback)(dev_t, ino_t));

void RegisterNativeHooksForPendingModules();

std::vector<std::pair<dev_t, ino_t>> GetNativeLoadedModules();

void RegisterPathGuardOnModule(dev_t dev, ino_t inode);

void InstallPropertyHooks(bool installOptionalHooks);

void InstallPropertyHooks(bool installSystemHooks, bool installDlopenHooks);

void InstallPropertyHooks(bool installPropertyGet,
                          bool installPropertyFind,
                          bool installPropertyReadCallback,
                          bool installSystemHooks,
                          bool installDlopenHooks);

void MarkNativeHooksInstalled();

void InstallSystemHooks();

void InstallGpuHooks();

// Installs the native /proc + /sys read guard (maps/smaps/meminfo/sys-net/
// readlink) across every loaded module. Registers only; the caller commits.
void InstallPathGuardHooks();

// Re-applies registered PLT hooks on .so files loaded after the initial install.
void InstallDlopenHooks();

void InstallJavaHooks(JNIEnv* env, const char* packageName);

void InstallSystemServerHooks(JNIEnv* env);

bool InitLSPlant(JNIEnv* env);

}  // namespace ds
