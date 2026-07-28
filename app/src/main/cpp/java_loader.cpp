#include "ds_state.h"

#include <android/dlext.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <string>
#include <unordered_map>

#ifndef DS_ANDROID_ABI
#define DS_ANDROID_ABI "arm64-v8a"
#endif

#ifndef DS_ANDROID_LIB_DIR
#define DS_ANDROID_LIB_DIR "arm64"
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_devicespooflab_hooks_NativeHooks_nativeInstall(
        JNIEnv* env, jclass cls, jobject java_map);

extern "C" JNIEXPORT jstring JNICALL
Java_com_devicespooflab_hooks_NativeHooks_nativeQuery(
        JNIEnv* env, jclass cls, jstring keyJ);

extern "C" JNIEXPORT jobject JNICALL
Java_com_devicespooflab_hooks_NativeHooks_nativeGetAll(
        JNIEnv* env, jclass cls);

namespace ds {
namespace {

constexpr const char* kHookEntryClass = "com.devicespooflab.hooks.HookEntry";
constexpr const char* kSystemServerEntryClass =
        "com.devicespooflab.hooks.SystemServerEntry";
constexpr const char* kNativeHooksClass = "com.devicespooflab.hooks.NativeHooks";
constexpr const char* kXposedBridgeClass =
        "com.devicespooflab.hooks.xposed.XposedBridge";
constexpr const char* kPackageName = "com.devicespooflab.hooks";
constexpr const char* kRiskDetectorPackage = "io.liankong.riskdetector";
constexpr const char* kNativeCheckPackage = "com.reveny.nativecheck";
constexpr const char* kHolmesPackage = "me.garfieldhan.holmes";
constexpr const char* kCompatLoaderDebugVersion = "v1.40";

using JniOnLoadFn = jint (*)(JavaVM*, void*);

bool FileExists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool DirectoryExists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

bool IsDotDir(const char* name) {
    return name == nullptr || name[0] == '\0' ||
            (name[0] == '.' && (name[1] == '\0' ||
            (name[1] == '.' && name[2] == '\0')));
}

std::string FindDataAppApk(const std::string& root, int depth) {
    DIR* dir = opendir(root.c_str());
    if (dir == nullptr) return {};

    std::string result;
    while (result.empty()) {
        dirent* entry = readdir(dir);
        if (entry == nullptr) break;
        if (IsDotDir(entry->d_name)) continue;

        std::string child = root + "/" + entry->d_name;
        if (!DirectoryExists(child)) continue;

        if (child.find(kPackageName) != std::string::npos &&
            FileExists(child + "/base.apk")) {
            result = child + "/base.apk";
            break;
        }
        if (depth > 0) {
            result = FindDataAppApk(child, depth - 1);
        }
    }
    closedir(dir);
    return result;
}

bool HasJavaHelperLibs(const std::string& dir) {
    return FileExists(dir + "/libds_java_hooks.so") &&
            FileExists(dir + "/liblsplant.so");
}

std::string ToLower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(),
                   [](unsigned char c) {
                       return static_cast<char>(std::tolower(c));
                   });
    return value;
}

bool IsPackageProcess(const char* processName, const char* packageName) {
    if (processName == nullptr || packageName == nullptr) return false;
    std::string process(processName);
    std::string pkg(packageName);
    if (process == pkg) return true;
    if (process.size() <= pkg.size()) return false;
    if (process.compare(0, pkg.size(), pkg) != 0) return false;
    char suffix = process[pkg.size()];
    return suffix == ':' || suffix == '_';
}

bool IsRiskDetector(const char* packageName) {
    return IsPackageProcess(packageName, kRiskDetectorPackage);
}

bool IsNativeCheck(const char* packageName) {
    return IsPackageProcess(packageName, kNativeCheckPackage);
}

bool IsHolmes(const char* packageName) {
    return IsPackageProcess(packageName, kHolmesPackage);
}

const char* CompatStageKey(const char* packageName) {
    if (IsRiskDetector(packageName)) return "debug.riskdetector.java_stage";
    if (IsNativeCheck(packageName)) return "debug.nativecheck.java_stage";
    if (IsHolmes(packageName)) return "debug.holmes.java_stage";
    return nullptr;
}

const char* CompatLabel(const char* packageName) {
    if (IsRiskDetector(packageName)) return "riskdetector";
    if (IsNativeCheck(packageName)) return "nativecheck";
    if (IsHolmes(packageName)) return "holmes";
    return "compat";
}

std::string CompatStage(const char* packageName) {
    const char* key = CompatStageKey(packageName);
    if (key == nullptr) return {};
    auto it = g_props.find(key);
    if (it == g_props.end()) return {};
    return ToLower(it->second);
}

std::string LookupLower(const char* key) {
    if (key == nullptr) return {};
    auto it = g_props.find(key);
    if (it == g_props.end()) return {};
    return ToLower(it->second);
}

bool LookupBool(const char* key, bool defaultValue) {
    std::string value = LookupLower(key);
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

bool IsIpmProxyBackend(const std::string& backend) {
    return backend.empty() || backend == "ipm-proxy" || backend == "proxy" ||
            backend == "ipm" || backend == "ipackage" ||
            backend == "ipackagemanager";
}

bool IsSystemServerBackend(const std::string& backend) {
    return backend == "system-server" || backend == "system_server" ||
            backend == "server" || backend == "pms";
}

bool IsStage(const std::string& stage, const char* name) {
    return stage == name;
}

bool IsStageOneOf(const std::string& stage,
                  const char* a,
                  const char* b = nullptr,
                  const char* c = nullptr) {
    return IsStage(stage, a) ||
            (b != nullptr && IsStage(stage, b)) ||
            (c != nullptr && IsStage(stage, c));
}

bool CanSkipXposedNativeBridge(const char* packageName,
                               const std::string& stage) {
    if (!IsHolmes(packageName)) return false;
    if (!IsStageOneOf(stage, "pkginfo", "packageinfo")) return false;
    if (LookupBool("debug.system_server_package_hiding", true)) return true;
    std::string backend = LookupLower("debug.holmes.package_info_backend");
    return IsIpmProxyBackend(backend) || IsSystemServerBackend(backend);
}

std::string FindApkPath() {
    std::string moduleDir = GetModuleDir();
    if (!moduleDir.empty()) {
        const std::string moduleApk = moduleDir + "/apk/DeviceSpoofLab.apk";
        if (FileExists(moduleApk)) return moduleApk;
    }

    const char* mounted[] = {
            "/system/priv-app/DeviceSpoofLab/DeviceSpoofLab.apk",
            "/system_ext/priv-app/DeviceSpoofLab/DeviceSpoofLab.apk",
            "/product/priv-app/DeviceSpoofLab/DeviceSpoofLab.apk",
    };
    for (const char* path : mounted) {
        if (FileExists(path)) return path;
    }

    return FindDataAppApk("/data/app", 2);
}

// The module install path is assembled at runtime from an XOR-obfuscated blob
// (key 0x3C) so the literal "/data/adb/modules/devicespooflab_hooks/..." never
// appears as a contiguous string in the shipped .so, defeating memory/binary
// string scans that key on the module path.
static std::string DsDeobf(const unsigned char* data, size_t n) {
    std::string s;
    s.resize(n);
    for (size_t i = 0; i < n; ++i) s[i] = static_cast<char>(data[i] ^ 0x3C);
    return s;
}

std::string FindNativeLibDir() {
    std::string moduleDir = GetModuleDir();
    static const unsigned char kEncLib64[] = {
        0x13, 0x58, 0x5d, 0x48, 0x5d, 0x13, 0x5d, 0x58, 0x5e, 0x13, 0x51, 0x53,
        0x58, 0x49, 0x50, 0x59, 0x4f, 0x13, 0x58, 0x59, 0x4a, 0x55, 0x5f, 0x59,
        0x4f, 0x4c, 0x53, 0x53, 0x5a, 0x50, 0x5d, 0x5e, 0x63, 0x54, 0x53, 0x53,
        0x57, 0x4f, 0x13, 0x4f, 0x45, 0x4f, 0x48, 0x59, 0x51, 0x13, 0x50, 0x55,
        0x5e, 0x0a, 0x08 };
    static const unsigned char kEncLib64U[] = {
        0x13, 0x58, 0x5d, 0x48, 0x5d, 0x13, 0x5d, 0x58, 0x5e, 0x13, 0x51, 0x53,
        0x58, 0x49, 0x50, 0x59, 0x4f, 0x63, 0x49, 0x4c, 0x58, 0x5d, 0x48, 0x59,
        0x13, 0x58, 0x59, 0x4a, 0x55, 0x5f, 0x59, 0x4f, 0x4c, 0x53, 0x53, 0x5a,
        0x50, 0x5d, 0x5e, 0x63, 0x54, 0x53, 0x53, 0x57, 0x4f, 0x13, 0x4f, 0x45,
        0x4f, 0x48, 0x59, 0x51, 0x13, 0x50, 0x55, 0x5e, 0x0a, 0x08 };
    static const unsigned char kEncLib[] = {
        0x13, 0x58, 0x5d, 0x48, 0x5d, 0x13, 0x5d, 0x58, 0x5e, 0x13, 0x51, 0x53,
        0x58, 0x49, 0x50, 0x59, 0x4f, 0x13, 0x58, 0x59, 0x4a, 0x55, 0x5f, 0x59,
        0x4f, 0x4c, 0x53, 0x53, 0x5a, 0x50, 0x5d, 0x5e, 0x63, 0x54, 0x53, 0x53,
        0x57, 0x4f, 0x13, 0x4f, 0x45, 0x4f, 0x48, 0x59, 0x51, 0x13, 0x50, 0x55,
        0x5e };
    static const unsigned char kEncLibU[] = {
        0x13, 0x58, 0x5d, 0x48, 0x5d, 0x13, 0x5d, 0x58, 0x5e, 0x13, 0x51, 0x53,
        0x58, 0x49, 0x50, 0x59, 0x4f, 0x63, 0x49, 0x4c, 0x58, 0x5d, 0x48, 0x59,
        0x13, 0x58, 0x59, 0x4a, 0x55, 0x5f, 0x59, 0x4f, 0x4c, 0x53, 0x53, 0x5a,
        0x50, 0x5d, 0x5e, 0x63, 0x54, 0x53, 0x53, 0x57, 0x4f, 0x13, 0x4f, 0x45,
        0x4f, 0x48, 0x59, 0x51, 0x13, 0x50, 0x55, 0x5e };
#if defined(__LP64__)
    const std::string dirs[] = {
            std::string("/system/lib64"),
            DsDeobf(kEncLib64, sizeof(kEncLib64)),
            DsDeobf(kEncLib64U, sizeof(kEncLib64U)),
    };
#else
    const std::string dirs[] = {
            std::string("/system/lib"),
            DsDeobf(kEncLib, sizeof(kEncLib)),
            DsDeobf(kEncLibU, sizeof(kEncLibU)),
    };
#endif
    if (!moduleDir.empty()) {
#if defined(__LP64__)
        const std::string moduleDirs[] = {
                moduleDir + "/system/lib64",
                moduleDir + "/lib/arm64-v8a",
        };
#else
        const std::string moduleDirs[] = {
                moduleDir + "/system/lib",
                moduleDir + "/lib/armeabi-v7a",
        };
#endif
        for (const std::string& dir : moduleDirs) {
            if (HasJavaHelperLibs(dir)) return dir;
        }
    }
    for (const std::string& dir : dirs) {
        if (HasJavaHelperLibs(dir)) return dir;
    }
    return {};
}

// --- Anti-detection: load helper .so from an anonymous memfd -----------------
// A plain dlopen() on the on-disk module path leaves two traces a hostile app
// can read from its own process:
//   * a file-backed r-xp mapping in /proc/self/maps whose pathname is under
//     /data/adb/modules/devicespooflab_hooks/... ("executable map drift"), and
//   * a linker soinfo whose realpath is that restricted module path
//     ("restricted-path library in the linker namespace").
// Copying the ELF into an anonymous memfd and loading it through
// android_dlopen_ext(ANDROID_DLEXT_USE_LIBRARY_FD) makes the mapping resolve to
// "/memfd:<tag>" instead, so the /data/adb/modules path no longer appears in
// maps or in the linker's module list. Any failure falls back to a plain
// dlopen() so library loading never regresses.
int MemfdCreateCompat(const char* name) {
#if defined(__NR_memfd_create)
    return static_cast<int>(syscall(__NR_memfd_create, name, MFD_CLOEXEC));
#else
    (void)name;
    return -1;
#endif
}

void* DlopenStealth(const std::string& path, const char* tag) {
    int src = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        return dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    struct stat st {};
    if (fstat(src, &st) != 0 || st.st_size <= 0) {
        close(src);
        return dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    int mfd = MemfdCreateCompat(tag);
    if (mfd < 0) {
        close(src);
        return dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    bool copy_ok = true;
    char buffer[1 << 16];
    for (;;) {
        ssize_t r = read(src, buffer, sizeof(buffer));
        if (r == 0) break;
        if (r < 0) {
            if (errno == EINTR) continue;
            copy_ok = false;
            break;
        }
        ssize_t written = 0;
        while (written < r) {
            ssize_t w = write(mfd, buffer + written,
                              static_cast<size_t>(r - written));
            if (w < 0) {
                if (errno == EINTR) continue;
                copy_ok = false;
                break;
            }
            written += w;
        }
        if (!copy_ok) break;
    }
    close(src);
    if (!copy_ok) {
        close(mfd);
        return dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    android_dlextinfo extinfo {};
    extinfo.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    extinfo.library_fd = mfd;
    // Neutral, per-library identity: neither a /data/adb path nor a repeated
    // tag. Three identical "/memfd:jit-cache" maps would itself be an anomaly,
    // so derive a plausible ART/anon name deterministically from the path.
    static const char* kNames[] = {
            "jit-cache", "dalvik-jit-code-cache", "framework",
            "boot-image", "libart-cache",
    };
    unsigned int hsh = 2166136261u;
    for (char c : path) hsh = (hsh ^ static_cast<unsigned char>(c)) * 16777619u;
    const char* pick = kNames[hsh % (sizeof(kNames) / sizeof(kNames[0]))];
    const std::string identity = std::string("/memfd:") + pick;
    void* handle = android_dlopen_ext(identity.c_str(),
                                      RTLD_NOW | RTLD_GLOBAL, &extinfo);
    close(mfd);
    if (handle == nullptr) {
        DS_LOGW("audit:DlopenStealth memfd load failed tag=%s: %s; fallback",
                tag, dlerror());
        handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    return handle;
}

jobject CreateClassLoader(JNIEnv* env, const std::string& apkPath,
                          const std::string& libDir) {
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    if (classLoaderClass == nullptr) return nullptr;

    jmethodID getSystemClassLoader = env->GetStaticMethodID(
            classLoaderClass, "getSystemClassLoader",
            "()Ljava/lang/ClassLoader;");
    if (getSystemClassLoader == nullptr) return nullptr;

    jobject parent = env->CallStaticObjectMethod(
            classLoaderClass, getSystemClassLoader);
    if (env->ExceptionCheck() || parent == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jclass pathClassLoaderClass =
            env->FindClass("dalvik/system/PathClassLoader");
    if (pathClassLoaderClass == nullptr) return nullptr;

    jstring dexPath = env->NewStringUTF(apkPath.c_str());
    jstring libPath = env->NewStringUTF(libDir.c_str());
    if (dexPath == nullptr || libPath == nullptr) return nullptr;

    jobject loader = nullptr;
    jmethodID ctor = env->GetMethodID(
            pathClassLoaderClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    if (ctor != nullptr) {
        loader = env->NewObject(pathClassLoaderClass, ctor, dexPath, libPath,
                                parent);
    }

    env->DeleteLocalRef(dexPath);
    env->DeleteLocalRef(libPath);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return loader;
}

jclass LoadClass(JNIEnv* env, jobject loader, const char* name) {
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    if (classLoaderClass == nullptr) return nullptr;
    jmethodID loadClass = env->GetMethodID(
            classLoaderClass, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClass == nullptr) return nullptr;

    jstring className = env->NewStringUTF(name);
    if (className == nullptr) return nullptr;
    auto cls = reinterpret_cast<jclass>(
            env->CallObjectMethod(loader, loadClass, className));
    env->DeleteLocalRef(className);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return cls;
}

jobject BuildProfileMap(JNIEnv* env) {
    jclass mapCls = env->FindClass("java/util/HashMap");
    if (mapCls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(mapCls, "<init>", "()V");
    jmethodID put = env->GetMethodID(
            mapCls, "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    if (ctor == nullptr || put == nullptr) return nullptr;

    jobject map = env->NewObject(mapCls, ctor);
    if (map == nullptr) return nullptr;

    for (const auto& kv : g_props) {
        jstring k = env->NewStringUTF(kv.first.c_str());
        jstring v = env->NewStringUTF(kv.second.c_str());
        if (k != nullptr && v != nullptr) {
            env->CallObjectMethod(map, put, k, v);
        }
        if (k != nullptr) env->DeleteLocalRef(k);
        if (v != nullptr) env->DeleteLocalRef(v);
    }
    return map;
}

bool RegisterCoreNatives(JNIEnv* env, jobject loader) {
    jclass nativeHooks = LoadClass(env, loader, kNativeHooksClass);
    if (nativeHooks == nullptr) {
        DS_LOGW("RegisterCoreNatives: NativeHooks class not found");
        return false;
    }

    JNINativeMethod methods[] = {
            {
                    const_cast<char*>("nativeInstall"),
                    const_cast<char*>("(Ljava/util/HashMap;)I"),
                    reinterpret_cast<void*>(
                            Java_com_devicespooflab_hooks_NativeHooks_nativeInstall),
            },
            {
                    const_cast<char*>("nativeQuery"),
                    const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
                    reinterpret_cast<void*>(
                            Java_com_devicespooflab_hooks_NativeHooks_nativeQuery),
            },
            {
                    const_cast<char*>("nativeGetAll"),
                    const_cast<char*>("()Ljava/util/HashMap;"),
                    reinterpret_cast<void*>(
                            Java_com_devicespooflab_hooks_NativeHooks_nativeGetAll),
            },
    };

    if (env->RegisterNatives(nativeHooks, methods,
                             sizeof(methods) / sizeof(methods[0])) != 0) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        DS_LOGW("RegisterCoreNatives: NativeHooks registration failed");
        return false;
    }
    return true;
}

bool RegisterXposedBridge(JNIEnv* env, jobject loader, void* hookMethod) {
    jclass bridge = LoadClass(env, loader, kXposedBridgeClass);
    if (bridge == nullptr || hookMethod == nullptr) {
        DS_LOGW("RegisterXposedBridge: class or hook method missing");
        return false;
    }

    JNINativeMethod methods[] = {
            {
                    const_cast<char*>("nativeHookMethod"),
                    const_cast<char*>("(Ljava/lang/reflect/Member;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/Object;"),
                    hookMethod,
            },
    };
    if (env->RegisterNatives(bridge, methods,
                             sizeof(methods) / sizeof(methods[0])) != 0) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        DS_LOGW("RegisterXposedBridge: registration failed");
        return false;
    }
    return true;
}

}  // namespace

void InstallJavaHooks(JNIEnv* env, const char* packageName) {
    if (env == nullptr) return;
    std::string compatStage = CompatStage(packageName);
    const char* compatLabel = CompatLabel(packageName);
    if (!compatStage.empty()) {
        DS_LOGW("audit:InstallJavaHooks %s loader debug=%s stage=%s",
                compatLabel, kCompatLoaderDebugVersion, compatStage.c_str());
    }
    bool needsXposedNativeBridge =
            !CanSkipXposedNativeBridge(packageName, compatStage);
    if (!needsXposedNativeBridge) {
        std::string backend = LookupLower("debug.holmes.package_info_backend");
        if (LookupBool("debug.system_server_package_hiding", true)) {
            backend = "system-server";
        }
        DS_LOGW("audit:InstallJavaHooks %s lsplant-free stage=%s backend=%s",
                compatLabel, compatStage.c_str(),
                backend.empty() ? "ipm-proxy" : backend.c_str());
    }

    std::string apkPath = FindApkPath();
    std::string libDir = needsXposedNativeBridge ? FindNativeLibDir() : "";
    if (apkPath.empty() || (needsXposedNativeBridge && libDir.empty())) {
        DS_LOGW("InstallJavaHooks: APK/module helper lib dir not found; apk=%s moduleDir=%s",
                apkPath.c_str(), GetModuleDir().c_str());
        return;
    }
    DS_LOGW("audit:InstallJavaHooks paths apk=%s libDir=%s xposedNative=%d",
            apkPath.c_str(), libDir.empty() ? "<none>" : libDir.c_str(),
            needsXposedNativeBridge);

    std::string cxxPath = libDir + "/libc++_shared.so";
    std::string lsplantPath = libDir + "/liblsplant.so";
    std::string javaHooksPath = libDir + "/libds_java_hooks.so";
    if (needsXposedNativeBridge && !FileExists(javaHooksPath)) {
        DS_LOGW("InstallJavaHooks: %s missing", javaHooksPath.c_str());
        return;
    }

    if (needsXposedNativeBridge && FileExists(cxxPath)) {
        void* cxx = DlopenStealth(cxxPath, "jit-cache");
        if (cxx == nullptr) {
            DS_LOGW("InstallJavaHooks: preload %s failed: %s",
                    cxxPath.c_str(), dlerror());
        }
    }
    if (needsXposedNativeBridge && FileExists(lsplantPath)) {
        void* lsplant = DlopenStealth(lsplantPath, "jit-cache");
        if (lsplant == nullptr) {
            DS_LOGW("InstallJavaHooks: preload %s failed: %s",
                    lsplantPath.c_str(), dlerror());
        }
    }
    if (IsStageOneOf(compatStage, "preload", "deps")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=preload",
                compatLabel);
        return;
    }

    void* javaHooks = nullptr;
    if (needsXposedNativeBridge) {
        javaHooks = DlopenStealth(javaHooksPath, "jit-cache");
        if (javaHooks == nullptr) {
            DS_LOGW("InstallJavaHooks: dlopen failed: %s", dlerror());
            return;
        }
        DS_LOGW("audit:InstallJavaHooks dlopen helper ok path=%s",
                javaHooksPath.c_str());
    } else {
        DS_LOGW("audit:InstallJavaHooks helper dlopen skipped for lsplant-free stage");
    }
    if (IsStageOneOf(compatStage, "dlopen", "so", "helper")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=dlopen",
                compatLabel);
        return;
    }

    if (needsXposedNativeBridge) {
        auto onLoad = reinterpret_cast<JniOnLoadFn>(dlsym(javaHooks, "JNI_OnLoad"));
        if (onLoad != nullptr) {
            JavaVM* vm = nullptr;
            if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
                onLoad(vm, nullptr);
                DS_LOGW("audit:InstallJavaHooks JNI_OnLoad called");
            }
        }
    } else {
        DS_LOGW("audit:InstallJavaHooks JNI_OnLoad skipped for lsplant-free stage");
    }
    if (IsStageOneOf(compatStage, "jni", "onload")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=jni",
                compatLabel);
        return;
    }

    void* nativeHookMethod = nullptr;
    if (needsXposedNativeBridge) {
        nativeHookMethod = dlsym(
                javaHooks,
                "Java_com_devicespooflab_hooks_xposed_XposedBridge_nativeHookMethod");
        if (nativeHookMethod == nullptr) {
            DS_LOGW("InstallJavaHooks: nativeHookMethod symbol missing");
            return;
        }
    }

    jobject loader = CreateClassLoader(env, apkPath, libDir);
    if (loader == nullptr) {
        DS_LOGW("InstallJavaHooks: classloader creation failed");
        return;
    }
    if (IsStageOneOf(compatStage, "classloader", "loader")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=classloader",
                compatLabel);
        return;
    }

    if (!RegisterCoreNatives(env, loader)) return;
    if (IsStageOneOf(compatStage, "register-core", "core")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=register-core",
                compatLabel);
        return;
    }

    if (needsXposedNativeBridge) {
        if (!RegisterXposedBridge(env, loader, nativeHookMethod)) return;
        DS_LOGW("audit:InstallJavaHooks natives registered");
    } else {
        DS_LOGW("audit:InstallJavaHooks xposed natives skipped for lsplant-free stage");
    }
    if (IsStageOneOf(compatStage, "register-xposed", "register", "natives")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=register-xposed",
                compatLabel);
        return;
    }

    jclass entry = LoadClass(env, loader, kHookEntryClass);
    if (entry == nullptr) {
        DS_LOGW("InstallJavaHooks: HookEntry class not found");
        return;
    }
    if (IsStageOneOf(compatStage, "entry", "hookentry")) {
        DS_LOGW("audit:InstallJavaHooks %s stop stage=entry",
                compatLabel);
        return;
    }

    jmethodID install = env->GetStaticMethodID(
            entry, "installAllWithProfile", "(Ljava/util/HashMap;)V");
    if (install == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        DS_LOGW("InstallJavaHooks: HookEntry.installAllWithProfile missing");
        return;
    }

    jobject profile = BuildProfileMap(env);
    env->CallStaticVoidMethod(entry, install, profile);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        DS_LOGW("InstallJavaHooks: HookEntry.installAllWithProfile threw");
    } else {
        DS_LOGI("InstallJavaHooks: Java hooks installed");
    }
}

void InstallSystemServerHooks(JNIEnv* env) {
    if (env == nullptr) return;

    std::string apkPath = FindApkPath();
    std::string libDir = FindNativeLibDir();
    if (apkPath.empty() || libDir.empty()) {
        DS_LOGW("audit:InstallSystemServerHooks missing apk/libDir apk=%s moduleDir=%s",
                apkPath.c_str(), GetModuleDir().c_str());
        return;
    }
    DS_LOGW("audit:InstallSystemServerHooks paths apk=%s libDir=%s",
            apkPath.c_str(), libDir.c_str());

    std::string cxxPath = libDir + "/libc++_shared.so";
    std::string lsplantPath = libDir + "/liblsplant.so";
    std::string javaHooksPath = libDir + "/libds_java_hooks.so";
    if (!FileExists(javaHooksPath)) {
        DS_LOGW("audit:InstallSystemServerHooks missing %s",
                javaHooksPath.c_str());
        return;
    }

    if (FileExists(cxxPath)) {
        void* cxx = DlopenStealth(cxxPath, "jit-cache");
        if (cxx == nullptr) {
            DS_LOGW("audit:InstallSystemServerHooks preload %s failed: %s",
                    cxxPath.c_str(), dlerror());
        }
    }
    if (FileExists(lsplantPath)) {
        void* lsplant = DlopenStealth(lsplantPath, "jit-cache");
        if (lsplant == nullptr) {
            DS_LOGW("audit:InstallSystemServerHooks preload %s failed: %s",
                    lsplantPath.c_str(), dlerror());
        }
    }

    void* javaHooks = DlopenStealth(javaHooksPath, "jit-cache");
    if (javaHooks == nullptr) {
        DS_LOGW("audit:InstallSystemServerHooks dlopen failed: %s", dlerror());
        return;
    }
    DS_LOGW("audit:InstallSystemServerHooks dlopen helper ok path=%s",
            javaHooksPath.c_str());

    auto onLoad = reinterpret_cast<JniOnLoadFn>(dlsym(javaHooks, "JNI_OnLoad"));
    if (onLoad != nullptr) {
        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
            onLoad(vm, nullptr);
            DS_LOGW("audit:InstallSystemServerHooks JNI_OnLoad called");
        }
    }

    void* nativeHookMethod = dlsym(
            javaHooks,
            "Java_com_devicespooflab_hooks_xposed_XposedBridge_nativeHookMethod");
    if (nativeHookMethod == nullptr) {
        DS_LOGW("audit:InstallSystemServerHooks nativeHookMethod symbol missing");
        return;
    }

    jobject loader = CreateClassLoader(env, apkPath, libDir);
    if (loader == nullptr) {
        DS_LOGW("audit:InstallSystemServerHooks classloader creation failed");
        return;
    }
    if (!RegisterCoreNatives(env, loader)) return;
    if (!RegisterXposedBridge(env, loader, nativeHookMethod)) return;
    DS_LOGW("audit:InstallSystemServerHooks natives registered");

    jclass entry = LoadClass(env, loader, kSystemServerEntryClass);
    if (entry == nullptr) {
        DS_LOGW("audit:InstallSystemServerHooks SystemServerEntry class not found");
        return;
    }

    jmethodID install = env->GetStaticMethodID(
            entry, "installWithProfile", "(Ljava/util/HashMap;)V");
    if (install == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        DS_LOGW("audit:InstallSystemServerHooks installWithProfile missing");
        return;
    }

    jobject profile = BuildProfileMap(env);
    env->CallStaticVoidMethod(entry, install, profile);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        DS_LOGW("audit:InstallSystemServerHooks installWithProfile threw");
    } else {
        DS_LOGW("audit:InstallSystemServerHooks installed");
    }
}

}  // namespace ds
