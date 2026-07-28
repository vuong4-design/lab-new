#include "ds_state.h"
#include "zygisk.hpp"
#include "companion_protocol.h"
#include "companion_io.h"

#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

namespace {

// ---- Scope handling (companion-sourced, cached by the module per process) ----

// The package name of our own module app; never spoof its own process.
constexpr const char* kOwnPackage = "com.devicespooflab.hooks";
constexpr const char* kRiskDetectorPackage = "io.liankong.riskdetector";
constexpr const char* kNativeCheckPackage = "com.reveny.nativecheck";
constexpr const char* kHolmesPackage = "me.garfieldhan.holmes";
constexpr const char* kModuleBuildVersion = "v1.40-java-hook-controls";

// Read a length-prefixed line list from a fd opened on scope.list. The
// companion sends scope entries through RequestPairs (key = package, empty
// value), so a process-local set is built from that.
bool LoadScopeFromCompanion(int companionFd,
                            std::unordered_set<std::string>& out) {
    std::unordered_map<std::string, std::string> pairs;
    if (!ds::RequestPairs(companionFd, ds::REQ_SCOPE, pairs)) {
        return false;
    }
    for (auto& kv : pairs) {
        if (!kv.first.empty()) out.insert(kv.first);
    }
    return true;
}

bool MatchesScope(const std::string& processName,
                  const std::unordered_set<std::string>& scope,
                  std::string& matched) {
    auto exact = scope.find(processName);
    if (exact != scope.end()) {
        matched = *exact;
        return true;
    }
    for (const std::string& pkg : scope) {
        if (pkg.empty() || processName.size() <= pkg.size()) continue;
        if (processName.compare(0, pkg.size(), pkg) != 0) continue;
        char suffix = processName[pkg.size()];
        if (suffix == ':' || suffix == '_') {
            matched = pkg;
            return true;
        }
    }
    matched.clear();
    return false;
}

bool IsListDelimiter(char c) {
    return c == ',' || c == ';' || c == ' ' || c == '\t' ||
            c == '\r' || c == '\n';
}

std::unordered_set<std::string> ParsePackageList(const std::string& rawList) {
    std::unordered_set<std::string> out;
    std::string item;
    for (char c : rawList) {
        if (IsListDelimiter(c)) {
            if (!item.empty()) {
                out.insert(item);
                item.clear();
            }
            continue;
        }
        item.push_back(c);
    }
    if (!item.empty()) out.insert(item);
    return out;
}

bool MatchesPackageList(const std::string& processName,
                        const std::string& rawList,
                        std::string& matched) {
    std::unordered_set<std::string> packages = ParsePackageList(rawList);
    if (packages.empty()) {
        matched.clear();
        return false;
    }
    return MatchesScope(processName, packages, matched);
}

bool IsPackageProcess(const std::string& processName, const char* packageName) {
    if (packageName == nullptr || processName.empty()) return false;
    std::string pkg(packageName);
    if (processName == pkg) return true;
    if (processName.size() <= pkg.size()) return false;
    if (processName.compare(0, pkg.size(), pkg) != 0) return false;
    char suffix = processName[pkg.size()];
    return suffix == ':' || suffix == '_';
}

std::string ToLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) {
                       return static_cast<char>(std::tolower(c));
                   });
    return value;
}

bool IsEnabledStage(const std::string& rawStage) {
    std::string stage = ToLower(rawStage);
    return !stage.empty()
            && stage != "0"
            && stage != "none"
            && stage != "off"
            && stage != "false";
}

std::string LookupStringProperty(const char* key);

bool LookupBoolProperty(const char* key, bool defaultValue) {
    std::string value = ToLower(LookupStringProperty(key));
    if (value.empty()) return defaultValue;
    if (value == "1" || value == "true" || value == "yes" ||
            value == "on") {
        return true;
    }
    if (value == "0" || value == "false" || value == "no" ||
            value == "off" || value == "none") {
        return false;
    }
    return defaultValue;
}

std::string LookupStringProperty(const char* key) {
    std::string value;
    ds::LookupProperty(key, value);
    return value;
}

std::string JoinScopePackages(const std::unordered_set<std::string>& scope) {
    std::vector<std::string> sorted;
    sorted.reserve(scope.size());
    for (const std::string& item : scope) {
        if (!item.empty()) sorted.push_back(item);
    }
    std::sort(sorted.begin(), sorted.end());

    std::string joined;
    for (const std::string& item : sorted) {
        if (!joined.empty()) joined += ",";
        joined += item;
    }
    return joined;
}

int LookupIntProperty(const char* key, int defaultValue, int minValue,
                      int maxValue) {
    std::string value = LookupStringProperty(key);
    if (value.empty()) return defaultValue;
    char* end = nullptr;
    long parsed = strtol(value.c_str(), &end, 10);
    if (end == value.c_str()) return defaultValue;
    if (parsed < minValue) return minValue;
    if (parsed > maxValue) return maxValue;
    return static_cast<int>(parsed);
}

bool StartsWith(const std::string& value, const char* prefix) {
    if (prefix == nullptr) return false;
    std::string p(prefix);
    return value.size() >= p.size()
            && value.compare(0, p.size(), p) == 0;
}

bool IsLateNativeCheckMode(const std::string& mode) {
    return mode == "late"
            || StartsWith(mode, "late-")
            || StartsWith(mode, "late_")
            || StartsWith(mode, "late:");
}

std::string NativeCheckBaseMode(const std::string& mode) {
    if (mode == "late") return "get";
    if (StartsWith(mode, "late-") || StartsWith(mode, "late_") ||
            StartsWith(mode, "late:")) {
        return mode.substr(5);
    }
    return mode;
}

bool IsNativeCheckCoreMode(const std::string& mode) {
    return mode == "get"
            || mode == "callback" || mode == "cb"
            || mode == "find"
            || mode == "core" || mode == "property" || mode == "props"
            || mode == "system" || mode == "sys"
            || mode == "dlopen" || mode == "dl"
            || mode == "all" || mode == "full";
}

bool IsNativeCheckPropGetMode(const std::string& mode) {
    return mode == "get"
            || mode == "core" || mode == "property" || mode == "props"
            || mode == "system" || mode == "sys"
            || mode == "dlopen" || mode == "dl"
            || mode == "all" || mode == "full";
}

bool IsNativeCheckPropFindMode(const std::string& mode) {
    return mode == "find"
            || mode == "core" || mode == "property" || mode == "props"
            || mode == "system" || mode == "sys"
            || mode == "dlopen" || mode == "dl"
            || mode == "all" || mode == "full";
}

bool IsNativeCheckPropCallbackMode(const std::string& mode) {
    return mode == "callback" || mode == "cb" || mode == "find"
            || mode == "core" || mode == "property" || mode == "props"
            || mode == "system" || mode == "sys"
            || mode == "dlopen" || mode == "dl"
            || mode == "all" || mode == "full";
}

bool IsNativeCheckSystemMode(const std::string& mode) {
    return mode == "system" || mode == "sys"
            || mode == "all" || mode == "full";
}

bool IsNativeCheckDlopenMode(const std::string& mode) {
    return mode == "dlopen" || mode == "dl"
            || mode == "all" || mode == "full";
}

struct NativeHookPlan {
    std::string rawMode;
    std::string baseMode;
    bool late = false;
    int delayMs = 0;
    bool installCore = false;
    bool installPropGet = false;
    bool installPropFind = false;
    bool installPropCallback = false;
    bool installSystem = false;
    bool installDlopen = false;
};

NativeHookPlan BuildNativeHookPlan(std::string rawMode,
                                   int delayMs,
                                   const char* defaultMode) {
    NativeHookPlan plan;
    plan.rawMode = ToLower(rawMode);
    if (plan.rawMode.empty()) {
        plan.rawMode = defaultMode == nullptr ? "full" : defaultMode;
    }
    plan.late = IsLateNativeCheckMode(plan.rawMode);
    plan.baseMode = NativeCheckBaseMode(plan.rawMode);
    plan.delayMs = plan.late ? delayMs : 0;
    plan.installCore = IsNativeCheckCoreMode(plan.baseMode);
    plan.installPropGet = IsNativeCheckPropGetMode(plan.baseMode);
    plan.installPropFind = IsNativeCheckPropFindMode(plan.baseMode);
    plan.installPropCallback = IsNativeCheckPropCallbackMode(plan.baseMode);
    plan.installSystem = IsNativeCheckSystemMode(plan.baseMode);
    plan.installDlopen = IsNativeCheckDlopenMode(plan.baseMode);
    return plan;
}

void InstallNativeHookPlan(const NativeHookPlan& plan,
                           const std::string& packageName) {
    if (!plan.installCore) {
        // Treat off/unknown as an explicit native policy. Without reserving,
        // the Java fallback path could install the default native hooks later.
        ds::MarkNativeHooksInstalled();
        DS_LOGW("audit:native hooks skipped package=%s nativeMode=%s baseMode=%s",
                packageName.c_str(), plan.rawMode.c_str(),
                plan.baseMode.c_str());
        return;
    }

    if (plan.late) {
        // Reserve native installation now so Java fallback cannot install the
        // same PLT hooks immediately before the delayed policy fires.
        ds::MarkNativeHooksInstalled();
        std::thread([propGet = plan.installPropGet,
                     propFind = plan.installPropFind,
                     propCallback = plan.installPropCallback,
                     systemHooks = plan.installSystem,
                     dlopenHooks = plan.installDlopen,
                     delayMs = plan.delayMs,
                     mode = plan.rawMode,
                     package = packageName]() {
            DS_LOGW("audit:late native hook scheduled package=%s mode=%s delayMs=%d",
                    package.c_str(), mode.c_str(), delayMs);
            if (delayMs > 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(delayMs));
            }
            DS_LOGW("audit:late native hook begin package=%s mode=%s",
                    package.c_str(), mode.c_str());
            ds::InstallPropertyHooks(propGet, propFind, propCallback,
                                     systemHooks, dlopenHooks);
            DS_LOGW("audit:late native hook end package=%s mode=%s",
                    package.c_str(), mode.c_str());
        }).detach();
        return;
    }

    ds::InstallPropertyHooks(plan.installPropGet,
                             plan.installPropFind,
                             plan.installPropCallback,
                             plan.installSystem,
                             plan.installDlopen);
    ds::MarkNativeHooksInstalled();
}

// ---- Module ----

class DeviceSpoofModule : public zygisk::ModuleBase {
public:
    void onLoad(Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
        ds::SetZygiskApi(api);
        // JNIEnv is thread-bound; the JavaVM is process-stable. Cache the VM so
        // we can fetch the calling thread's JNIEnv at hook time instead of
        // reusing this (possibly stale, wrong-thread) env_.
        if (env != nullptr) env->GetJavaVM(&vm_);
        module_dir_fd_ = api_ != nullptr ? api_->getModuleDir() : -1;
        if (module_dir_fd_ >= 0) {
            char fdPath[64] = {};
            snprintf(fdPath, sizeof(fdPath), "/proc/self/fd/%d", module_dir_fd_);
            // Use the fd only while resolving/loading helper libs. Some
            // APatch/SELinux setups deny direct path traversal under
            // /data/adb/modules from app processes, while the fd returned by
            // Zygisk stays usable through /proc/self/fd. We close it in
            // closeModuleDir() right after the helper libs are loaded so no
            // descriptor pointing at /data/adb/modules survives into the
            // running app (previously this fd was kept open for the whole
            // process, which a detector flagged as a suspicious fd target).
            ds::SetModuleDir(fdPath);
        }
        DS_LOGW("audit:onLoad api=%p env=%p vm=%p", api_, env_, vm_);
        DS_LOGW("audit:moduleBuild=%s", kModuleBuildVersion);
        DS_LOGW("audit:moduleDir=%s", ds::GetModuleDir().c_str());
    }

    void preAppSpecialize(AppSpecializeArgs* args) override {
        // Reset per-process state (the module object is static and reused
        // across forks within the same zygote).
        should_spoof_ = false;
        profile_.clear();
        package_name_.clear();

        if (args == nullptr || args->nice_name == nullptr) {
            dontUnload();
            return;
        }

        const char* niceName =
            env_->GetStringUTFChars(args->nice_name, nullptr);
        if (niceName == nullptr) {
            dontUnload();
            return;
        }
        std::string packageName(niceName);
        env_->ReleaseStringUTFChars(args->nice_name, niceName);
        package_name_ = packageName;
        DS_LOGW("audit:preApp package=%s", packageName.c_str());

        // Never spoof our own UI/editor process.
        if (IsPackageProcess(packageName, kOwnPackage)) {
            DS_LOGW("audit:preApp package=%s own package; unloading",
                    packageName.c_str());
            cleanProcess();
            return;
        }

        // connectCompanion only works in pre-specialize (still zygote priv).
        int companionFd = api_->connectCompanion();
        if (companionFd < 0) {
            DS_LOGW("audit:preApp package=%s companion connect failed",
                    packageName.c_str());
            cleanProcess();
            return;
        }

        std::unordered_set<std::string> scope;
        bool scopeOk = LoadScopeFromCompanion(companionFd, scope);
        std::string matchedScope;
        bool inScope = MatchesScope(packageName, scope, matchedScope);
        DS_LOGW("audit:preApp package=%s scopeOk=%d scopeSize=%zu inScope=%d matched=%s",
                packageName.c_str(), scopeOk, (size_t)scope.size(), inScope,
                matchedScope.c_str());
        if (!scopeOk || !inScope) {
            close(companionFd);
            cleanProcess();
            return;
        }

        // In scope: pull the full profile now, while we still can talk to the
        // companion. Apply it in postAppSpecialize once the process is live.
        should_spoof_ = ds::RequestPairs(companionFd, ds::REQ_PROFILE, profile_);
        close(companionFd);
        if (!should_spoof_) {
            DS_LOGW("audit:preApp package=%s profile read failed on scope connection; retrying",
                    packageName.c_str());
            int profileFd = api_->connectCompanion();
            if (profileFd >= 0) {
                should_spoof_ = ds::RequestPairs(profileFd, ds::REQ_PROFILE, profile_);
                close(profileFd);
            }
        }
        DS_LOGW("audit:preApp package=%s profileOk=%d profileSize=%zu",
                packageName.c_str(), should_spoof_, (size_t)profile_.size());

        if (!should_spoof_) {
            cleanProcess();
            return;
        }
        // Keep the library mapped; we hook in postAppSpecialize.
        dontUnload();
    }

    void postAppSpecialize(const AppSpecializeArgs*) override {
        DS_LOGW("audit:postApp shouldSpoof=%d profileSize=%zu",
                should_spoof_, (size_t)profile_.size());
        if (!should_spoof_) {
            closeModuleDir();
            return;
        }
        applyProfileAndHook(/*installJavaHooks=*/true);
        // Helper libs are loaded (into anonymous memfds); drop the module-dir
        // fd so no descriptor into /data/adb/modules survives into the app.
        closeModuleDir();
    }

    void preServerSpecialize(ServerSpecializeArgs*) override {
        // system_server: pull the profile so native property hooks (uname,
        // __system_property_*) cover the system process too.
        profile_.clear();
        should_spoof_ = false;
        package_name_ = "system_server";

        int companionFd = api_->connectCompanion();
        if (companionFd < 0) {
            DS_LOGW("audit:preServer companion connect failed");
            dontUnload();
            return;
        }
        should_spoof_ = ds::RequestPairs(companionFd, ds::REQ_PROFILE, profile_);
        std::unordered_set<std::string> scope;
        bool scopeOk = LoadScopeFromCompanion(companionFd, scope);
        close(companionFd);
        if (scopeOk) {
            profile_["hooks.scope_packages"] = JoinScopePackages(scope);
        }
        DS_LOGW("audit:preServer profileOk=%d profileSize=%zu scopeOk=%d scopeSize=%zu",
                should_spoof_, (size_t)profile_.size(), scopeOk,
                (size_t)scope.size());
        dontUnload();
    }

    void postServerSpecialize(const ServerSpecializeArgs*) override {
        DS_LOGW("audit:postServer shouldSpoof=%d profileSize=%zu",
                should_spoof_, (size_t)profile_.size());
        if (!should_spoof_) {
            closeModuleDir();
            return;
        }
        applyProfileAndHook(/*installJavaHooks=*/false);
        closeModuleDir();
    }

private:
    Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    JavaVM* vm_ = nullptr;
    int module_dir_fd_ = -1;
    bool should_spoof_ = false;
    std::string package_name_;
    std::unordered_map<std::string, std::string> profile_;

    void dontUnload() {
        // Default: keep the library; nothing to do. Present for symmetry.
    }

    // Close the Zygisk module-dir fd and forget its /proc/self/fd path. Called
    // once helper libs are loaded (DlopenStealth copies them into anonymous
    // memfds, so nothing on disk is needed afterwards). Removes the
    // "open descriptor -> /data/adb/modules/..." trace. Idempotent.
    void closeModuleDir() {
        if (module_dir_fd_ >= 0) {
            close(module_dir_fd_);
            module_dir_fd_ = -1;
        }
        ds::SetModuleDir(std::string());
    }

    void cleanProcess() {
        // Not in scope: drop all of our files from this process's mount
        // namespace and unload the library so we leave no trace.
        closeModuleDir();
        api_->setOption(zygisk::FORCE_DENYLIST_UNMOUNT);
        api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    void applyProfileAndHook(bool installJavaHooks) {
        DS_LOGW("audit:applyProfile installJava=%d profileSize=%zu",
                installJavaHooks, (size_t)profile_.size());
        if (profile_.empty()) {
            DS_LOGW("audit:applyProfile skipped empty profile");
            return;
        }
        // Hand the profile to the existing native layer and install the PLT
        // hooks that were previously driven through JNI nativeInstall().
        ds::g_props = std::move(profile_);
        std::string brand;
        std::string model;
        std::string fingerprint;
        ds::LookupProperty("ro.product.brand", brand);
        ds::LookupProperty("ro.product.model", model);
        ds::LookupProperty("ro.build.fingerprint", fingerprint);
        DS_LOGW("audit:profile loaded keys=%zu brand=%s model=%s fp=%s",
                (size_t)ds::g_props.size(), brand.c_str(), model.c_str(),
                fingerprint.c_str());
        bool riskDetectorCompat =
                IsPackageProcess(package_name_, kRiskDetectorPackage);
        bool nativeCheckCompat =
                IsPackageProcess(package_name_, kNativeCheckPackage);
        bool holmesCompat =
                IsPackageProcess(package_name_, kHolmesPackage);
        std::string globalNativeRawMode = installJavaHooks
                ? ToLower(LookupStringProperty("debug.native_hook_mode"))
                : "full";
        int globalNativeDelayMs = installJavaHooks
                ? LookupIntProperty("debug.native_hook_delay_ms", 0, 0, 60000)
                : 0;
        NativeHookPlan nativePlan =
                BuildNativeHookPlan(globalNativeRawMode, globalNativeDelayMs,
                                    "full");

        std::string riskDetectorJavaStage;
        bool riskDetectorJavaDebug = false;
        if (riskDetectorCompat) {
            riskDetectorJavaStage =
                    LookupStringProperty("debug.riskdetector.java_stage");
            riskDetectorJavaDebug = IsEnabledStage(riskDetectorJavaStage);
            std::string riskDetectorRawNativeMode =
                    ToLower(LookupStringProperty("debug.riskdetector.native_mode"));
            int riskDetectorNativeDelayMs = LookupIntProperty(
                    "debug.riskdetector.native_delay_ms",
                    globalNativeDelayMs, 0, 60000);
            nativePlan = BuildNativeHookPlan(riskDetectorRawNativeMode,
                                             riskDetectorNativeDelayMs,
                                             "core");
            DS_LOGW("audit:riskdetector compat active javaStage=%s javaDebug=%d nativeMode=%s rawMode=%s late=%d delayMs=%d core=%d propGet=%d propFind=%d propCb=%d system=%d dlopen=%d",
                    riskDetectorJavaStage.empty() ? "none" : riskDetectorJavaStage.c_str(),
                    riskDetectorJavaDebug, nativePlan.baseMode.c_str(),
                    nativePlan.rawMode.c_str(), nativePlan.late,
                    nativePlan.delayMs, nativePlan.installCore,
                    nativePlan.installPropGet, nativePlan.installPropFind,
                    nativePlan.installPropCallback, nativePlan.installSystem,
                    nativePlan.installDlopen);
        }
        std::string nativeCheckJavaStage;
        bool nativeCheckJavaDebug = false;
        if (nativeCheckCompat) {
            nativeCheckJavaStage =
                    LookupStringProperty("debug.nativecheck.java_stage");
            std::string nativeCheckRawNativeMode =
                    ToLower(LookupStringProperty("debug.nativecheck.native_mode"));
            int nativeCheckNativeDelayMs = LookupIntProperty(
                    "debug.nativecheck.native_delay_ms",
                    globalNativeDelayMs > 0 ? globalNativeDelayMs : 3000,
                    0, 60000);
            nativeCheckJavaDebug = IsEnabledStage(nativeCheckJavaStage);
            nativePlan = BuildNativeHookPlan(nativeCheckRawNativeMode,
                                             nativeCheckNativeDelayMs,
                                             "late-full");
            DS_LOGW("audit:nativecheck compat active javaStage=%s javaDebug=%d nativeMode=%s rawMode=%s late=%d delayMs=%d core=%d propGet=%d propFind=%d propCb=%d system=%d dlopen=%d",
                    nativeCheckJavaStage.empty() ? "none" : nativeCheckJavaStage.c_str(),
                    nativeCheckJavaDebug, nativePlan.baseMode.c_str(),
                    nativePlan.rawMode.c_str(), nativePlan.late,
                    nativePlan.delayMs, nativePlan.installCore,
                    nativePlan.installPropGet, nativePlan.installPropFind,
                    nativePlan.installPropCallback, nativePlan.installSystem,
                    nativePlan.installDlopen);
        }
        std::string holmesJavaStage;
        bool holmesJavaDebug = false;
        if (holmesCompat) {
            holmesJavaStage = LookupStringProperty("debug.holmes.java_stage");
            if (holmesJavaStage.empty()) holmesJavaStage = "pkginfo";
            holmesJavaDebug = IsEnabledStage(holmesJavaStage);
            std::string holmesRawNativeMode =
                    ToLower(LookupStringProperty("debug.holmes.native_mode"));
            int holmesNativeDelayMs = LookupIntProperty(
                    "debug.holmes.native_delay_ms",
                    globalNativeDelayMs > 0 ? globalNativeDelayMs : 3000,
                    0, 60000);
            nativePlan = BuildNativeHookPlan(holmesRawNativeMode,
                                             holmesNativeDelayMs,
                                             "late-full");
            DS_LOGW("audit:holmes compat active javaStage=%s javaDebug=%d nativeMode=%s rawMode=%s late=%d delayMs=%d core=%d propGet=%d propFind=%d propCb=%d system=%d dlopen=%d",
                    holmesJavaStage.c_str(), holmesJavaDebug,
                    nativePlan.baseMode.c_str(), nativePlan.rawMode.c_str(),
                    nativePlan.late, nativePlan.delayMs,
                    nativePlan.installCore, nativePlan.installPropGet,
                    nativePlan.installPropFind, nativePlan.installPropCallback,
                    nativePlan.installSystem, nativePlan.installDlopen);
        }

        DS_LOGW("audit:native hook plan package=%s nativeMode=%s rawMode=%s late=%d delayMs=%d core=%d propGet=%d propFind=%d propCb=%d system=%d dlopen=%d",
                package_name_.c_str(), nativePlan.baseMode.c_str(),
                nativePlan.rawMode.c_str(), nativePlan.late,
                nativePlan.delayMs, nativePlan.installCore,
                nativePlan.installPropGet, nativePlan.installPropFind,
                nativePlan.installPropCallback, nativePlan.installSystem,
                nativePlan.installDlopen);
        InstallNativeHookPlan(nativePlan, package_name_);

        bool globalJavaHooksEnabled =
                LookupBoolProperty("debug.java_hooks", true);
        std::string javaDisabledMatched;
        bool packageJavaDisabled = installJavaHooks &&
                MatchesPackageList(package_name_,
                                   LookupStringProperty("debug.java_hooks_disabled_packages"),
                                   javaDisabledMatched);
        bool effectiveJava = installJavaHooks
                && globalJavaHooksEnabled
                && !packageJavaDisabled
                && (!riskDetectorCompat || riskDetectorJavaDebug)
                && (!nativeCheckCompat || nativeCheckJavaDebug)
                && (!holmesCompat || holmesJavaDebug);
        DS_LOGW("audit:native hooks requested installNative=%d lateNative=%d propGet=%d propFind=%d propCb=%d systemNative=%d dlopenNative=%d installJava=%d effectiveJava=%d globalJava=%d javaDisabled=%d matched=%s",
                nativePlan.installCore, nativePlan.late,
                nativePlan.installPropGet, nativePlan.installPropFind,
                nativePlan.installPropCallback, nativePlan.installSystem,
                nativePlan.installDlopen,
                installJavaHooks, effectiveJava, globalJavaHooksEnabled,
                packageJavaDisabled, javaDisabledMatched.c_str());

        // App-side compatibility gates. system_server is handled below through
        // a narrow PackageManager hook entry so target apps do not need local
        // package-hiding hooks.
        if (riskDetectorCompat && !riskDetectorJavaDebug) {
            DS_LOGW("audit:java hooks skipped for io.liankong.riskdetector compat stage=none");
            return;
        }
        if (nativeCheckCompat && !nativeCheckJavaDebug) {
            DS_LOGW("audit:java hooks skipped for com.reveny.nativecheck compat stage=none");
            return;
        }
        if (holmesCompat && !holmesJavaDebug) {
            DS_LOGW("audit:java hooks skipped for me.garfieldhan.holmes compat stage=none");
            return;
        }
        if (!installJavaHooks) {
            bool serverHooks =
                    LookupBoolProperty("debug.system_server_hooks", true);
            if (!serverHooks) {
                DS_LOGW("audit:system_server hooks disabled by debug.system_server_hooks=0");
                return;
            }
            bool serverPackageHiding =
                    LookupBoolProperty("debug.system_server_package_hiding", true);
            bool serverFeatureSpoofing =
                    LookupBoolProperty("debug.system_server_feature_spoofing", true);
            bool serverAccountHiding =
                    LookupBoolProperty("debug.system_server_account_hiding", true) &&
                    LookupBoolProperty("hooks.hide_accounts", false);
            bool serverBatterySpoofing =
                    LookupBoolProperty("debug.system_server_battery_spoofing", true);
            if (serverPackageHiding || serverFeatureSpoofing || serverAccountHiding ||
                    serverBatterySpoofing) {
                if (vm_ != nullptr) {
                    JNIEnv* env = nullptr;
                    if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)
                            == JNI_OK && env != nullptr) {
                        ds::InstallSystemServerHooks(env);
                    } else {
                        DS_LOGW("audit:system_server hooks skipped no JNIEnv");
                    }
                } else {
                    DS_LOGW("audit:system_server hooks skipped vm=null");
                }
            } else {
                DS_LOGW("audit:system_server hooks disabled by config");
            }
            return;
        }
        if (!globalJavaHooksEnabled) {
            DS_LOGW("audit:java hooks skipped by debug.java_hooks=0");
            return;
        }
        if (packageJavaDisabled) {
            DS_LOGW("audit:java hooks skipped package=%s matched=%s by debug.java_hooks_disabled_packages",
                    package_name_.c_str(), javaDisabledMatched.c_str());
            return;
        }

        // Keep the preload core self-contained for APatch/ZygiskNext, then
        // load Java hook classes from the installed UI APK while native helper
        // libraries come from the module/system path.
        if (vm_ != nullptr) {
            JNIEnv* env = nullptr;
            if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)
                    == JNI_OK && env != nullptr) {
                ds::InstallJavaHooks(env, package_name_.c_str());
            } else {
                DS_LOGW("applyProfileAndHook: no JNIEnv for Java hook loader");
            }
        } else {
            DS_LOGW("audit:java hooks skipped vm=null");
        }
    }
};

}  // namespace

REGISTER_ZYGISK_MODULE(DeviceSpoofModule)
